package kr.junhyung.mcagents.bot;

import kr.junhyung.mcagents.protocol.Frame;
import kr.junhyung.mcagents.protocol.FrameCodec;
import kr.junhyung.mcagents.protocol.Messages;
import kr.junhyung.mcagents.protocol.ProtocolViolation;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * One bot's connection, from the server's side.
 *
 * <p>A single thread reads and applies frames. That is not incidental: {@code run-command} sends a
 * command, waits, and then drains the chat lines the server replied with, which only works if
 * events reach the buffer before the result that followed them on the wire. Anything slow is
 * handed off; the applying itself stays in order.
 */
public final class BotLink implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BotLink.class);

    /** Grace on top of a call's deadline before the server stops waiting for the bot. */
    private static final long CALL_GRACE_MS = 2_000;

    private static final int DEAD_AFTER_MISSED_BEATS = 3;

    private final Socket socket;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService timers;
    private final OutputStream out;
    private final InputStream in;

    private final AtomicLong nextCallId = new AtomicLong(1);
    private final Map<Long, PendingCall> pending = new ConcurrentHashMap<>();
    private final Map<UUID, byte[]> blobs = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile Messages.Hello hello;
    private volatile long lastHeardFrom = System.currentTimeMillis();
    private volatile long ackedEventSeq;

    public BotLink(Socket socket, ObjectMapper mapper, ScheduledExecutorService timers) throws IOException {
        this.socket = socket;
        this.mapper = mapper;
        this.timers = timers;
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        this.out = new BufferedOutputStream(socket.getOutputStream());
        this.in = new BufferedInputStream(socket.getInputStream());
    }

    public Messages.Hello hello() {
        return hello;
    }

    public void acceptHello(Messages.Hello frame) {
        this.hello = frame;
    }

    /**
     * Read the one frame that is allowed to come first.
     *
     * <p>Doing this before {@link #pump} rather than as a flag inside it is what makes "a frame
     * before hello is a violation" structural: there is no path that applies a message while the
     * server still does not know which bot sent it.
     */
    public Messages.Hello awaitHello(int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        try {
            Frame first = FrameCodec.read(in);

            if (!(first instanceof Frame.Json json)) {
                throw new ProtocolViolation(ProtocolViolation.Code.BLOB_BEFORE_HELLO,
                        "the first frame on a link must be hello, not a blob");
            }

            Messages.FromBot message;
            try {
                message = mapper.readValue(json.payload(), Messages.FromBot.class);
            } catch (JacksonException e) {
                throw new ProtocolViolation(ProtocolViolation.Code.MALFORMED_JSON,
                        "the first message could not be read: " + e.getOriginalMessage());
            }

            if (!(message instanceof Messages.Hello frame)) {
                throw new ProtocolViolation(ProtocolViolation.Code.HELLO_EXPECTED,
                        "the first message on a link must be hello");
            }

            acceptHello(frame);
            lastHeardFrom = System.currentTimeMillis();
            return frame;
        } finally {
            socket.setSoTimeout(0);
        }
    }

    /**
     * Say why the link is closing, to the bot and to this server's own log.
     *
     * <p>Both, because a bot that cannot read the fault reconnects and does the same thing again,
     * and whoever is watching the server sees a link close for no stated reason.
     */
    public void fault(String code, String message) {
        log.warn("closing a bot link: {} {}", code, message);
        try {
            send(new Messages.Fault(code, message));
        } catch (IOException | RuntimeException ignored) {
            // The link is already in a state where saying so may not be possible.
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * What the link hands upward. Results never reach it: the link settles those itself, so the
     * order a caller sees is the order the wire had. Neither does {@code hello}, which the link
     * has already taken in {@link #awaitHello} and treats as a violation a second time.
     */
    public interface Sink {
        void event(Messages.Event event);

        void status(Messages.Status status);

        void log(Messages.Log log);
    }

    /**
     * Read and apply frames until the link closes. One thread, in arrival order: {@code
     * run-command} drains the chat lines a command produced, which only holds if those events
     * reached the buffer before the result that followed them.
     */
    public void pump(Sink sink) {
        try {
            while (!closed.get()) {
                Frame frame = FrameCodec.read(in);
                lastHeardFrom = System.currentTimeMillis();

                switch (frame) {
                    case Frame.Blob blob -> rememberBlob(blob.id(), blob.content());
                    case Frame.Json json -> apply(mapper.readValue(json.payload(), Messages.FromBot.class), sink);
                }
            }
        } catch (ProtocolViolation e) {
            fault(e.code().name(), e.getMessage());
        } catch (IOException e) {
            // A closed link is how this ends; the caller finds out through the failing calls.
        } catch (JacksonException e) {
            /*
            Jackson 3 throws unchecked, so a bot sending a message this server cannot read used to
            leave the exception on the reader thread: the link closed without a word, the bot
            reconnected, and it did the same thing again. It is a breach of the contract like any
            other, and the bot is told which one.
            */
            fault(ProtocolViolation.Code.MALFORMED_JSON.name(), e.getOriginalMessage());
        } finally {
            close();
        }
    }

    private void apply(Messages.FromBot message, Sink sink) throws ProtocolViolation {
        switch (message) {
            case Messages.Result result -> settle(result.id(), result);
            case Messages.Event event -> sink.event(event);
            case Messages.Status status -> sink.status(status);
            case Messages.Log log -> sink.log(log);
            case Messages.Hello frame -> throw new ProtocolViolation(ProtocolViolation.Code.HELLO_TWICE,
                    "hello arrived twice on one link (second said botName \"%s\")".formatted(frame.botName()));
            case Messages.Pong pong -> noteAck(pong.nonce());
        }
    }

    public synchronized void send(Messages.ToBot message) throws IOException {
        FrameCodec.write(out, new Frame.Json(mapper.writeValueAsBytes(message)));
    }

    public void rememberBlob(UUID id, byte[] content) {
        blobs.put(id, content);
    }

    public byte[] takeBlob(UUID id) {
        return blobs.remove(id);
    }

    public void noteAck(long seq) {
        ackedEventSeq = Math.max(ackedEventSeq, seq);
    }

    public long ackedEventSeq() {
        return ackedEventSeq;
    }

    /**
     * Send a call and wait for its answer.
     *
     * <p>The bot arms {@code deadlineMs} and the server arms a little more, so in the ordinary case
     * the bot's timer fires first and answers with a timeout it can describe. When the server's
     * fires first the bot has gone quiet, and saying so is different from saying the tool failed.
     */
    public CompletableFuture<Messages.Result> call(String tool, Map<String, Object> args, int deadlineMs) {
        return request(tool, deadlineMs, id -> new Messages.Call(id, tool, args, deadlineMs, null));
    }

    /**
     * Send anything the bot answers with a {@code result} and wait for that answer.
     *
     * <p>{@code connect} and {@code disconnect} carry an id for the same reason {@code call} does,
     * so they go through the same machinery: one answer per id, one timer, and a link that drops
     * fails them the same way it fails a tool.
     */
    public CompletableFuture<Messages.Result> request(String label, int deadlineMs,
            java.util.function.LongFunction<Messages.ToBot> build) {
        long id = nextCallId.getAndIncrement();
        PendingCall call = new PendingCall();
        pending.put(id, call);

        call.timeout = timers.schedule(() -> abandon(id, label, deadlineMs), deadlineMs + CALL_GRACE_MS,
                TimeUnit.MILLISECONDS);

        try {
            send(build.apply(id));
        } catch (IOException e) {
            settle(id, failed(id, "bot", "LINK_LOST",
                    "the link to the bot dropped while sending \"%s\": %s".formatted(label, e.getMessage())));
        }
        return call.future;
    }

    /** A result the server is no longer waiting for is dropped, not treated as a breach. */
    public void settle(long id, Messages.Result result) {
        PendingCall call = pending.remove(id);
        if (call == null) {
            return;
        }
        if (call.timeout != null) {
            call.timeout.cancel(false);
        }
        call.future.complete(result);
    }

    private void abandon(long id, String tool, int deadlineMs) {
        PendingCall call = pending.remove(id);
        if (call == null) {
            return;
        }
        try {
            send(new Messages.Cancel(id, "server_timeout"));
        } catch (IOException ignored) {
            // The link is already suspect; the caller is told either way.
        }
        call.future.complete(failed(id, "bot", "NO_ANSWER",
                "the bot did not answer \"%s\" within %dms. The link may be stalled; check get-bot-status."
                        .formatted(tool, deadlineMs + CALL_GRACE_MS)));
    }

    /** Every call still in flight fails when the link goes. None of them is retried. */
    public void failEverythingInFlight(String reason) {
        for (Long id : Set.copyOf(pending.keySet())) {
            PendingCall call = pending.remove(id);
            if (call != null) {
                if (call.timeout != null) {
                    call.timeout.cancel(false);
                }
                call.future.complete(failed(id, "bot", "LINK_LOST", reason));
            }
        }
    }

    public boolean looksDead(int heartbeatMs) {
        if (!pending.isEmpty()) {
            return false;
        }
        return System.currentTimeMillis() - lastHeardFrom > (long) heartbeatMs * DEAD_AFTER_MISSED_BEATS;
    }

    public int inFlight() {
        return pending.size();
    }

    private static Messages.Result failed(long id, String errorClass, String code, String message) {
        return new Messages.Result(id, false, message, null, null,
                new Messages.Failure(errorClass, code, message, false, null), 0);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        failEverythingInFlight("the link to the bot closed");
        blobs.clear();
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing useful to do with a failure to close.
        }
    }

    private static final class PendingCall {
        private final CompletableFuture<Messages.Result> future = new CompletableFuture<>();
        private volatile ScheduledFuture<?> timeout;
    }
}
