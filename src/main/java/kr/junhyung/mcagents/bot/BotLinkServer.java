package kr.junhyung.mcagents.bot;

import kr.junhyung.mcagents.catalog.Catalog;
import kr.junhyung.mcagents.catalog.ToolSpec;
import kr.junhyung.mcagents.protocol.Frame;
import kr.junhyung.mcagents.protocol.Messages;
import kr.junhyung.mcagents.protocol.ProtocolViolation;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;
import tools.jackson.databind.ObjectMapper;

/**
 * Where bots dial in.
 *
 * <p>The direction is the point: a pod that comes up finds the server rather than the other way
 * round, so nothing here tracks pod addresses, and a bot running on a laptop attaches to a server
 * in a cluster without either of them being routable to the other.
 *
 * <p>A NetworkPolicy is the first gate on the port, and the link token the second: a pod that
 * gets past the policy still cannot introduce itself as a bot without the token the operator
 * handed both sides. A server given no token takes any hello, which is what lets the server, the
 * bots and the operator each ship this on their own.
 */
public class BotLinkServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(BotLinkServer.class);

    /** A bot that has connected but not introduced itself is dropped. */
    public static final int HELLO_TIMEOUT_MS = 5_000;

    private static final int HEARTBEAT_MS = 5_000;

    /** Every feed a bot pushes, in the order the protocol lists them. */
    public static final List<String> FEEDS = List.of("chat", "actionBar", "title", "dialog", "effect", "toast");

    /** What every bot is told at the handshake, from the same constants the link enforces. */
    private static final Map<String, Object> LIMITS = Map.of(
            "frameBytes", Frame.MAX_FRAME_BYTES,
            "jsonFrameBytes", Frame.MAX_JSON_BYTES,
            "blobBytes", BotLink.BLOB_BYTES,
            "pendingBlobBytes", BotLink.PENDING_BLOB_BYTES,
            "inFlightCalls", BotLink.IN_FLIGHT_CALLS,
            "deadlineCeilingMs", 600_000);

    private final Catalog catalog;
    private final BotRegistry bots;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService timers;
    private final int port;
    private final int repeatFlushMs;
    private final int helloTimeoutMs;
    private final Map<String, Boolean> events;
    private final byte[] linkToken;
    private final MeterRegistry meters;

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ServerSocket listener;
    private volatile Thread acceptor;

    /**
     * @param repeatFlushMs how often a bot re-sends a folded run while it stays open, so "still
     *                      showing" stays answerable. A run closes after three of these without a repeat
     * @param mutedFeeds    feeds bots are told not to push. {@code effect} on a busy server is a
     *                      firehose, and the valve is only worth having if it can be closed
     * @param linkToken     what a hello has to carry to be admitted. Blank admits every hello
     */
    public BotLinkServer(Catalog catalog, BotRegistry bots, ObjectMapper mapper,
            ScheduledExecutorService timers, int port, int repeatFlushMs, Set<String> mutedFeeds,
            String linkToken, MeterRegistry meters) {
        this(catalog, bots, mapper, timers, port, repeatFlushMs, mutedFeeds, linkToken, meters, HELLO_TIMEOUT_MS);
    }

    /** The hello timeout is a parameter so a test can watch a silent bot be dropped without waiting five seconds. */
    public BotLinkServer(Catalog catalog, BotRegistry bots, ObjectMapper mapper,
            ScheduledExecutorService timers, int port, int repeatFlushMs, Set<String> mutedFeeds,
            String linkToken, MeterRegistry meters, int helloTimeoutMs) {
        if (repeatFlushMs <= 0) {
            throw new IllegalArgumentException("repeatFlushMs has to be positive, and was " + repeatFlushMs);
        }
        for (String feed : mutedFeeds) {
            if (!FEEDS.contains(feed)) {
                throw new IllegalArgumentException("there is no feed called \"%s\" to mute; the feeds are %s"
                        .formatted(feed, FEEDS));
            }
        }

        this.catalog = catalog;
        this.bots = bots;
        this.mapper = mapper;
        this.timers = timers;
        this.port = port;
        this.repeatFlushMs = repeatFlushMs;
        this.helloTimeoutMs = helloTimeoutMs;
        this.linkToken = linkToken == null || linkToken.isBlank() ? null : linkToken.getBytes(StandardCharsets.UTF_8);
        this.meters = meters;

        Map<String, Boolean> valves = new LinkedHashMap<>();
        FEEDS.forEach(feed -> valves.put(feed, !mutedFeeds.contains(feed)));
        this.events = Collections.unmodifiableMap(valves);
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

        log.info("listening for bots on port {}{}", listener.getLocalPort(),
                linkToken == null ? ", taking any bot that dials in: no link token is set" : ", link token required");
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

    /**
     * Just inside the web server's phase: this starts after Tomcat is up and stops after Tomcat
     * has drained its requests, before it is torn down. The default phase stops first, which
     * closed every link while calls were still in flight, so a rollout failed whatever an agent
     * was in the middle of. Nothing is sent to the bots on the way out: a shutdown frame makes
     * both kinds exit the process instead of redialling the replica that replaces this one.
     */
    @Override
    public int getPhase() {
        return WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE - 512;
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
        Gauge rejectedTools = null;
        try {
            link = new BotLink(socket, mapper, timers);
            Messages.Hello hello = link.awaitHello(helloTimeoutMs);

            BotSession session = admit(link, hello);
            name = session.name();
            rejectedTools = Gauge.builder("mcagents.bots.rejected_tools", session, bot -> bot.rejectedTools().size())
                    .tag("bot", name)
                    .description("Tools this bot offered at the handshake that the server refused")
                    .register(meters);

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
        } catch (SocketTimeoutException e) {
            log.warn("a bot connected and never introduced itself within {}ms", helloTimeoutMs);
            if (link != null) {
                link.fault(ProtocolViolation.Code.HELLO_EXPECTED.name(),
                        "no hello arrived within %dms of connecting".formatted(helloTimeoutMs));
            }
        } catch (IOException e) {
            log.debug("a bot link ended: {}", e.toString());
        } finally {
            if (link != null) {
                link.close();
            }
            /*
            Gone before the name is free: a bot redialling under the same name registers its own
            gauge only once the registry lets it in, and must not find this one still there.
            */
            if (rejectedTools != null) {
                meters.remove(rejectedTools);
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
        /*
        Before the name is taken: an impostor must not be able to hold a name a real bot is about to
        dial in under. The comparison is constant-time so what it takes to refuse a token says
        nothing about how much of it was right, and the message names the bot and not the token.
        */
        if (linkToken != null && !MessageDigest.isEqual(linkToken,
                hello.linkToken() == null ? new byte[0] : hello.linkToken().getBytes(StandardCharsets.UTF_8))) {
            throw new Rejected("UNAUTHORIZED",
                    "bot \"%s\" did not present the link token this server requires".formatted(hello.botName()));
        }

        BotSession session = new BotSession(hello.botName(), hello.kind(), link, timers);
        Vetted vetted = vet(hello);
        session.acceptCapabilities(vetted.accepted());
        session.rejectCapabilities(vetted.rejected());
        /*
        One line per tool, because a tool that went dark at a handshake is the fact that explains a
        refusal an agent meets an hour later, and helloOk carries it only to the bot.
        */
        for (Messages.RejectedTool refused : vetted.rejected()) {
            log.warn("bot \"{}\" ({}) offered {} and it was refused: {}",
                    hello.botName(), hello.kind(), refused.tool(), refused.reason());
        }

        try {
            bots.add(session);
        } catch (IllegalStateException e) {
            throw new Rejected(e.getMessage().contains("given back") ? "GIVEN_BACK" : "NAME_TAKEN", e.getMessage());
        }

        link.send(new Messages.HelloOk(
                catalog.protocol(),
                UUID.randomUUID().toString(),
                HEARTBEAT_MS,
                repeatFlushMs,
                LIMITS,
                events,
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

        /**
         * The bot's fields ride as key-values rather than formatted into the message, so a
         * structured log format indexes them; the bot's name stays in the message for the plain
         * console, where key-values are not shown.
         */
        @Override
        public void log(Messages.Log entry) {
            LoggingEventBuilder line = BotLinkServer.log.atLevel(levelOf(entry.level()))
                    .addKeyValue("bot", session.name());
            Map<String, Object> fields = entry.fields() == null ? Map.of() : entry.fields();

            for (Map.Entry<String, Object> field : fields.entrySet()) {
                line = line.addKeyValue(field.getKey(), field.getValue());
            }
            line.log("bot \"{}\": {}", session.name(), entry.message());
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
