package kr.junhyung.mcagents.bot;

import kr.junhyung.mcagents.protocol.Frame;
import kr.junhyung.mcagents.protocol.FrameCodec;
import kr.junhyung.mcagents.protocol.Messages;
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

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * What the link hands upward. Results never reach it — the link settles those itself, so the
     * order a caller sees is the order the wire had.
     */
    public interface Sink {
        void event(Messages.Event event);

        void status(Messages.Status status);

        void log(Messages.Log log);

        void hello(Messages.Hello hello);
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
        } catch (IOException e) {
            // A closed link is how this ends; the caller finds out through the failing calls.
        } finally {
            close();
        }
    }

    private void apply(Messages.FromBot message, Sink sink) {
        switch (message) {
            case Messages.Result result -> settle(result.id(), result);
            case Messages.Event event -> sink.event(event);
            case Messages.Status status -> sink.status(status);
            case Messages.Log log -> sink.log(log);
            case Messages.Hello frame -> {
                acceptHello(frame);
                sink.hello(frame);
            }
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
        long id = nextCallId.getAndIncrement();
        PendingCall call = new PendingCall();
        pending.put(id, call);

        call.timeout = timers.schedule(() -> abandon(id, tool, deadlineMs), deadlineMs + CALL_GRACE_MS,
                TimeUnit.MILLISECONDS);

        try {
            send(new Messages.Call(id, tool, args, deadlineMs, null));
        } catch (IOException e) {
            settle(id, failed(id, "bot", "LINK_LOST",
                    "the link to the bot dropped while sending \"%s\": %s".formatted(tool, e.getMessage())));
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
