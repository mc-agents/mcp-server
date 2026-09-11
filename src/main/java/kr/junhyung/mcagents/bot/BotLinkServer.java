package kr.junhyung.mcagents.bot;

import kr.junhyung.mcagents.catalog.Catalog;
import kr.junhyung.mcagents.catalog.ToolSpec;
import kr.junhyung.mcagents.protocol.Messages;
import kr.junhyung.mcagents.protocol.ProtocolViolation;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import tools.jackson.databind.ObjectMapper;

/**
 * Where bots dial in.
 *
 * <p>The direction is the point: a pod that comes up finds the server rather than the other way
 * round, so nothing here tracks pod addresses, and a bot running on a laptop attaches to a server
 * in a cluster without either of them being routable to the other.
 *
 * <p>The port carries no authentication. A NetworkPolicy opens it to this server alone, and giving
 * every bot a rotating token would put secret rotation in the operator for a port that never
 * leaves the cluster. This is written down as a known limit in the README.
 */
public class BotLinkServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(BotLinkServer.class);

    /** A bot that has connected but not introduced itself is dropped. */
    private static final int HELLO_TIMEOUT_MS = 5_000;

    private static final int HEARTBEAT_MS = 5_000;

    /** How often a folded run is re-sent while it stays open, so "still showing" stays answerable. */
    private static final int REPEAT_FLUSH_MS = 1_000;

    private static final Map<String, Object> LIMITS = Map.of(
            "frameBytes", 16 * 1024 * 1024,
            "jsonFrameBytes", 1024 * 1024,
            "blobBytes", 8 * 1024 * 1024,
            "pendingBlobBytes", 32 * 1024 * 1024,
            "inFlightCalls", 8,
            "deadlineCeilingMs", 600_000);

    private final Catalog catalog;
    private final BotRegistry bots;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService timers;
    private final int port;

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ServerSocket listener;
    private volatile Thread acceptor;

    public BotLinkServer(Catalog catalog, BotRegistry bots, ObjectMapper mapper,
            ScheduledExecutorService timers, int port) {
        this.catalog = catalog;
        this.bots = bots;
        this.mapper = mapper;
        this.timers = timers;
        this.port = port;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            listener = new ServerSocket(port);
        } catch (IOException e) {
            running.set(false);
            throw new IllegalStateException("could not listen for bots on port %d".formatted(port), e);
        }

        acceptor = Thread.ofVirtual().name("bot-link-acceptor").start(this::accept);
        timers.scheduleWithFixedDelay(this::beat, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);

        log.info("listening for bots on port {}", listener.getLocalPort());
    }

    /** The bound port, which is not {@link #port} when the configuration asked for an ephemeral one. */
    public int boundPort() {
        ServerSocket open = listener;
        return open == null ? -1 : open.getLocalPort();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            listener.close();
        } catch (IOException ignored) {
            // Closing is how the accept loop ends; a failure to close changes nothing.
        }
        if (acceptor != null) {
            acceptor.interrupt();
        }
        bots.shutdown("the server is shutting down");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void accept() {
        while (running.get()) {
            try {
                Socket socket = listener.accept();
                Thread.ofVirtual().name("bot-link-" + socket.getPort()).start(() -> handle(socket));
            } catch (IOException e) {
                if (running.get()) {
                    log.warn("a bot connection could not be accepted", e);
                }
            }
        }
    }

    /**
     * One bot, from its TCP connect to its last frame.
     *
     * <p>The handshake happens on this thread before anything is registered, so a link that never
     * introduces itself never becomes a session an agent can see. {@code pump} then owns the thread
     * for the life of the link.
     */
    private void handle(Socket socket) {
        BotLink link = null;
        String name = null;
        try {
            link = new BotLink(socket, mapper, timers);
            Messages.Hello hello = link.awaitHello(HELLO_TIMEOUT_MS);

            BotSession session = admit(link, hello);
            name = session.name();

            log.info("bot \"{}\" ({}, mc {}) linked with {} of {} tools",
                    name, hello.kind(), hello.mcVersion(),
                    session.capabilityCount(), catalog.size());

            link.pump(new Feed(session));
        } catch (ProtocolViolation e) {
            log.warn("a bot broke the protocol during its handshake: {}", e.getMessage());
            if (link != null) {
                link.fault(e.code().name(), e.getMessage());
            }
        } catch (Rejected e) {
            log.warn("a bot was turned away: {}", e.getMessage());
            if (link != null) {
                link.fault(e.code, e.getMessage());
            }
        } catch (IOException e) {
            log.debug("a bot link ended: {}", e.toString());
        } finally {
            if (link != null) {
                link.close();
            }
            if (name != null) {
                bots.remove(name, "the link to the bot closed");
            }
        }
    }

    /**
     * Decide whether this bot may join, and tell it what the server settled.
     *
     * <p>Everything that can refuse the bot happens before it is added, because a session that
     * exists for a moment and then vanishes is one an agent can call into and get an answer that
     * contradicts itself.
     */
    private BotSession admit(BotLink link, Messages.Hello hello) throws Rejected, IOException {
        if (hello.protocols() == null || !hello.protocols().contains(catalog.protocol())) {
            throw new Rejected("PROTOCOL_UNSUPPORTED",
                    "this server speaks protocol %d; the bot offered %s"
                            .formatted(catalog.protocol(), hello.protocols()));
        }
        if (hello.botName() == null) {
            throw new Rejected("MISSING_FIELD", "hello did not carry a botName");
        }
        try {
            BotRegistry.requireValidName(hello.botName());
        } catch (IllegalArgumentException e) {
            throw new Rejected("BAD_NAME", e.getMessage());
        }

        BotSession session = new BotSession(hello.botName(), hello.kind(), link, timers);
        Vetted vetted = vet(hello);
        session.acceptCapabilities(vetted.accepted());

        try {
            bots.add(session);
        } catch (IllegalStateException e) {
            throw new Rejected("NAME_TAKEN", e.getMessage());
        }

        link.send(new Messages.HelloOk(
                catalog.protocol(),
                UUID.randomUUID().toString(),
                HEARTBEAT_MS,
                REPEAT_FLUSH_MS,
                LIMITS,
                Map.of("chat", true, "actionBar", true, "title", true, "dialog", true, "effect", true),
                vetted.accepted().stream().map(Messages.Capability::tool).toList(),
                vetted.rejected()));

        return session;
    }

    /**
     * Check what the bot says it implements against what the catalogue declares.
     *
     * <p>A tool the catalogue does not have is ignored, because the bot is newer than the server.
     * A tool whose argument schema hashes differently is disabled on its own, and the rest of the
     * bot keeps working — during a rolling update that is one tool dark for a few seconds instead
     * of a bot that cannot be used at all.
     */
    private Vetted vet(Messages.Hello hello) {
        List<Messages.Capability> accepted = new ArrayList<>();
        List<Messages.RejectedTool> rejected = new ArrayList<>();

        for (Messages.Capability capability : hello.capabilities() == null ? List.<Messages.Capability>of()
                : hello.capabilities()) {
            ToolSpec spec = catalog.get(capability.tool());

            if (spec == null) {
                continue;
            }
            if (!spec.supportedBy(hello.kind())) {
                rejected.add(new Messages.RejectedTool(capability.tool(),
                        "the catalogue does not offer this tool on a %s bot".formatted(hello.kind())));
                continue;
            }
            if (spec.wireSchemaHash() != null && !spec.wireSchemaHash().equals(capability.argsHash())) {
                rejected.add(new Messages.RejectedTool(capability.tool(),
                        "argument schema mismatch: the catalogue has %s, the bot compiled against %s"
                                .formatted(spec.wireSchemaHash(), capability.argsHash())));
                continue;
            }
            accepted.add(capability);
        }
        return new Vetted(accepted, rejected);
    }

    /**
     * Ping everyone, and drop the links that stopped answering.
     *
     * <p>A bot with calls in flight is never declared dead here: a sixty second walk is quiet by
     * design, and reaping it would turn a working tool into a lost bot.
     */
    private void beat() {
        for (BotSession session : bots.all()) {
            BotLink link = session.link();

            if (link.isClosed()) {
                bots.remove(session.name(), "the link to the bot closed");
                continue;
            }
            if (link.looksDead(HEARTBEAT_MS)) {
                log.warn("bot \"{}\" missed {}ms of heartbeats with nothing in flight; dropping it",
                        session.name(), HEARTBEAT_MS * 3);
                bots.remove(session.name(), "the bot stopped answering heartbeats");
                continue;
            }
            try {
                link.send(new Messages.Ping(System.nanoTime(), link.ackedEventSeq()));
            } catch (IOException e) {
                bots.remove(session.name(), "the link to the bot dropped: " + e.getMessage());
            }
        }
    }

    /** What a link hands to its session. Kept here so {@link BotSession} does not know about links. */
    private record Feed(BotSession session) implements BotLink.Sink {

        @Override
        public void event(Messages.Event event) {
            session.accept(event);
            session.link().noteAck(event.seq());
        }

        @Override
        public void status(Messages.Status status) {
            session.accept(status);
        }

        @Override
        public void log(Messages.Log entry) {
            BotLinkServer.log.atLevel(levelOf(entry.level())).log("bot \"{}\": {} {}",
                    session.name(), entry.message(), entry.fields() == null ? Map.of() : entry.fields());
        }

        private static org.slf4j.event.Level levelOf(String level) {
            return switch (level == null ? "info" : level) {
                case "error" -> org.slf4j.event.Level.ERROR;
                case "warn" -> org.slf4j.event.Level.WARN;
                case "debug", "trace" -> org.slf4j.event.Level.DEBUG;
                default -> org.slf4j.event.Level.INFO;
            };
        }
    }

    private record Vetted(List<Messages.Capability> accepted, List<Messages.RejectedTool> rejected) {}

    /** A bot the server will not take. It is told why, in a {@code fault}, before the link closes. */
    private static final class Rejected extends Exception {

        private static final long serialVersionUID = 1L;

        private final String code;

        private Rejected(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
