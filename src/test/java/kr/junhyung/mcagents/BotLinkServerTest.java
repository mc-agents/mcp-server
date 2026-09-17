package kr.junhyung.mcagents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import kr.junhyung.mcagents.bot.BotLinkServer;
import kr.junhyung.mcagents.bot.BotRegistry;
import kr.junhyung.mcagents.bot.BotSession;
import kr.junhyung.mcagents.catalog.Catalog;
import kr.junhyung.mcagents.catalog.ToolSpec;
import kr.junhyung.mcagents.protocol.Frame;
import kr.junhyung.mcagents.protocol.FrameCodec;
import kr.junhyung.mcagents.protocol.Messages;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The handshake, over a real socket. A bot is a separate process that we do not control, so what
 * matters is what the server does with one that gets it wrong.
 */
class BotLinkServerTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2);
    private final List<Socket> open = new ArrayList<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private Catalog catalog;
    private BotRegistry bots;
    private BotLinkServer server;

    @BeforeEach
    void start() {
        catalog = Catalog.load();
        bots = new BotRegistry(4);
        server = new BotLinkServer(catalog, bots, mapper, timers, 0, 1_000, Set.of(), "", meters);
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        for (Socket socket : open) {
            socket.close();
        }
        server.stop();
        timers.shutdownNow();
    }

    private Socket dial() throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.boundPort());
        socket.setSoTimeout(5_000);
        open.add(socket);
        return socket;
    }

    private void send(Socket socket, Messages.FromBot message) throws IOException {
        FrameCodec.write(socket.getOutputStream(), new Frame.Json(mapper.writeValueAsBytes(message)));
    }

    private Messages.ToBot read(Socket socket) throws IOException {
        Frame.Json frame = assertInstanceOf(Frame.Json.class, FrameCodec.read(socket.getInputStream()));
        return mapper.readValue(frame.payload(), Messages.ToBot.class);
    }

    private Messages.Hello hello(String name, String kind, List<Messages.Capability> capabilities) {
        return hello(name, kind, capabilities, null);
    }

    private Messages.Hello hello(String name, String kind, List<Messages.Capability> capabilities, String linkToken) {
        return new Messages.Hello(List.of(catalog.protocol()), name, kind, "0.1.0", "26.1.2",
                catalog.version(), capabilities, List.of("blob", "eventFold"), linkToken);
    }

    private void requireLinkToken(String token) {
        server.stop();
        server = new BotLinkServer(catalog, bots, mapper, timers, 0, 1_000, Set.of(), token, meters);
        server.start();
    }

    private double rejectedToolsGauge(String bot) {
        return meters.get("mcagents.bots.rejected_tools").tag("bot", bot).gauge().value();
    }

    /** Whatever the catalogue says this kind of bot can run, so the test does not pin a tool name. */
    private List<Messages.Capability> everything(String kind) {
        return catalog.all().stream()
                .filter(spec -> spec.route() == ToolSpec.Route.RPC && spec.supportedBy(kind))
                .map(spec -> new Messages.Capability(spec.name(), spec.wireSchemaHash()))
                .toList();
    }

    private BotSession awaitSession(String name) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                return bots.resolve(name);
            } catch (IllegalArgumentException notYet) {
                Thread.sleep(20);
            }
        }
        throw new AssertionError("bot \"%s\" never appeared in the registry".formatted(name));
    }

    @Test
    void aBotThatIntroducesItselfBecomesASessionAnAgentCanSee() throws Exception {
        Socket bot = dial();
        send(bot, hello("alice", "fabric", everything("fabric")));

        Messages.HelloOk ok = assertInstanceOf(Messages.HelloOk.class, read(bot));

        assertEquals(catalog.protocol(), ok.protocol());
        assertFalse(ok.acceptedTools().isEmpty());
        assertTrue(ok.rejectedTools().isEmpty(), () -> "unexpectedly rejected: " + ok.rejectedTools());

        BotSession session = awaitSession("alice");
        assertEquals("fabric", session.kind());
        assertEquals(ok.acceptedTools().size(), session.capabilityCount());
    }

    /**
     * The fold cadence and the feed valves are the server's to set, and a bot only honours what the
     * handshake carries. A server that muted a feed and still said true would have a valve that
     * closes nothing.
     */
    @Test
    void theHandshakeCarriesTheCadenceAndTheValvesThisServerWasGiven() throws Exception {
        server.stop();
        server = new BotLinkServer(catalog, bots, mapper, timers, 0, 250, Set.of("effect"), "", meters);
        server.start();

        Socket bot = dial();
        send(bot, hello("carol", "fabric", everything("fabric")));

        Messages.HelloOk ok = assertInstanceOf(Messages.HelloOk.class, read(bot));

        assertEquals(250, ok.repeatFlushMs());
        assertEquals(Map.of("chat", true, "actionBar", true, "title", true, "dialog", true, "effect", false,
                "toast", true), ok.events());
    }

    @Test
    void aFeedThatDoesNotExistCannotBeMuted() {
        assertThrows(IllegalArgumentException.class,
                () -> new BotLinkServer(catalog, bots, mapper, timers, 0, 1_000, Set.of("effects"), "", meters));
    }

    /*
    The bot is newer than the server here, which is the ordinary state of a rolling update. Making
    that fatal would mean a bot could never ship a tool before the server had heard of it.
    */
    @Test
    void aToolTheCatalogueDoesNotHaveIsIgnoredRatherThanRejected() throws Exception {
        Socket bot = dial();
        send(bot, hello("alice", "fabric",
                List.of(new Messages.Capability("invent-a-tool", "whatever"))));

        Messages.HelloOk ok = assertInstanceOf(Messages.HelloOk.class, read(bot));

        assertTrue(ok.acceptedTools().isEmpty());
        assertTrue(ok.rejectedTools().isEmpty());
        assertEquals(0, awaitSession("alice").capabilityCount());
    }

    /*
    A mismatched hash means the bot compiled against different arguments, so calling that tool
    would send it something it cannot read. Only that tool goes dark; the rest of the bot works.
    */
    @Test
    void aToolWhoseArgumentsDisagreeIsDisabledOnItsOwn() throws Exception {
        List<Messages.Capability> reported = new ArrayList<>(everything("fabric"));
        Messages.Capability first = reported.get(0);
        reported.set(0, new Messages.Capability(first.tool(), "a-hash-from-another-build"));

        Socket bot = dial();
        send(bot, hello("alice", "fabric", reported));

        Messages.HelloOk ok = assertInstanceOf(Messages.HelloOk.class, read(bot));

        assertEquals(1, ok.rejectedTools().size());
        assertEquals(first.tool(), ok.rejectedTools().get(0).tool());
        BotSession session = awaitSession("alice");
        assertFalse(session.supports(first.tool()));
        assertEquals(reported.size() - 1, ok.acceptedTools().size());

        /* Visible on the server's side too: the bot is the only one told by helloOk. */
        assertEquals(List.of(first.tool()), session.rejectedTools().stream().map(Messages.RejectedTool::tool).toList());
        assertEquals(1.0, rejectedToolsGauge("alice"));

        bot.close();
        for (int attempt = 0; attempt < 100 && bots.size() > 0; attempt++) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertNull(meters.find("mcagents.bots.rejected_tools").tag("bot", "alice").gauge(),
                "a bot that left does not keep reporting its dark tools");
    }

    @Test
    void aBotWithoutRejectedToolsReportsZeroOfThem() throws Exception {
        Socket bot = dial();
        send(bot, hello("alice", "fabric", everything("fabric")));
        assertInstanceOf(Messages.HelloOk.class, read(bot));
        awaitSession("alice");

        assertEquals(0.0, rejectedToolsGauge("alice"));
    }

    /*
    The NetworkPolicy is the first gate on the port and the token the second: a pod that gets
    past the policy still cannot introduce itself as a bot. The refusal comes before the name is
    taken, so an impostor cannot hold a name the real bot is about to dial in under.
    */
    @Test
    void aBotWithTheLinkTokenThisServerRequiresIsAdmitted() throws Exception {
        requireLinkToken("s3cret");

        Socket bot = dial();
        send(bot, hello("alice", "fabric", List.of(), "s3cret"));

        assertInstanceOf(Messages.HelloOk.class, read(bot));
        awaitSession("alice");
    }

    @Test
    void aBotWithoutTheLinkTokenThisServerRequiresIsRefusedBeforeItHasAName() throws Exception {
        requireLinkToken("s3cret");

        Socket silent = dial();
        send(silent, hello("alice", "fabric", List.of(), null));
        Messages.Fault fault = assertInstanceOf(Messages.Fault.class, read(silent));
        assertEquals("UNAUTHORIZED", fault.code());
        assertTrue(fault.message().contains("alice"), fault.message());
        assertFalse(fault.message().contains("s3cret"), "the token must not travel back in the refusal");
        assertThrows(EOFException.class, () -> read(silent));

        Socket wrong = dial();
        send(wrong, hello("alice", "fabric", List.of(), "s3cre"));
        assertEquals("UNAUTHORIZED", assertInstanceOf(Messages.Fault.class, read(wrong)).code());

        assertEquals(0, bots.size());

        Socket real = dial();
        send(real, hello("alice", "fabric", List.of(), "s3cret"));
        assertInstanceOf(Messages.HelloOk.class, read(real));
        awaitSession("alice");
    }

    /* Tolerant when unset, so the server can ship before the bots and the operator carry a token. */
    @Test
    void aServerWithNoLinkTokenIgnoresWhateverTheHelloCarries() throws Exception {
        Socket withToken = dial();
        send(withToken, hello("alice", "fabric", List.of(), "anything"));
        assertInstanceOf(Messages.HelloOk.class, read(withToken));

        Socket without = dial();
        send(without, hello("bob", "fabric", List.of(), null));
        assertInstanceOf(Messages.HelloOk.class, read(without));

        awaitSession("alice");
        awaitSession("bob");
    }

    @Test
    void aBotSpeakingAnotherProtocolIsToldSoAndClosed() throws Exception {
        Socket bot = dial();
        send(bot, new Messages.Hello(List.of(catalog.protocol() + 99), "alice", "fabric",
                "0.1.0", "26.1.2", catalog.version(), List.of(), List.of(), null));

        Messages.Fault fault = assertInstanceOf(Messages.Fault.class, read(bot));

        assertEquals("PROTOCOL_UNSUPPORTED", fault.code());
        assertThrows(EOFException.class, () -> read(bot));
        assertEquals(0, bots.size());
    }

    @Test
    void aSecondBotUnderTheSameNameIsTurnedAwayAndTheFirstKeepsWorking() throws Exception {
        Socket first = dial();
        send(first, hello("alice", "fabric", List.of()));
        assertInstanceOf(Messages.HelloOk.class, read(first));
        awaitSession("alice");

        Socket second = dial();
        send(second, hello("alice", "fabric", List.of()));

        assertEquals("NAME_TAKEN", assertInstanceOf(Messages.Fault.class, read(second)).code());
        assertEquals(1, bots.size());
        assertEquals("fabric", bots.resolve("alice").kind());
    }

    /*
    Taking any frame before hello would mean applying a message without knowing which bot sent it,
    so the rule is structural rather than a flag checked later.
    */
    @Test
    void aFrameBeforeHelloIsAViolation() throws Exception {
        Socket bot = dial();
        send(bot, new Messages.Log("info", "hello? is this thing on", null));

        assertEquals("HELLO_EXPECTED", assertInstanceOf(Messages.Fault.class, read(bot)).code());
        assertEquals(0, bots.size());
    }

    @Test
    void aBlobBeforeHelloIsAViolationOfItsOwn() throws Exception {
        Socket bot = dial();
        FrameCodec.write(bot.getOutputStream(), new Frame.Blob(UUID.randomUUID(), new byte[] {1, 2, 3}));

        assertEquals("BLOB_BEFORE_HELLO", assertInstanceOf(Messages.Fault.class, read(bot)).code());
        assertEquals(0, bots.size());
    }

    @Test
    void aHelloWithoutABotNameIsTurnedAway() throws Exception {
        Socket bot = dial();
        send(bot, hello(null, "fabric", List.of()));

        assertEquals("MISSING_FIELD", assertInstanceOf(Messages.Fault.class, read(bot)).code());
        assertEquals(0, bots.size());
    }

    /* The name becomes a pod name and a username, so one neither can carry is refused before a session exists. */
    @Test
    void aHelloWithANameNothingCouldRunUnderIsTurnedAway() throws Exception {
        Socket bot = dial();
        send(bot, hello("bad name!", "fabric", List.of()));

        Messages.Fault fault = assertInstanceOf(Messages.Fault.class, read(bot));

        assertEquals("BAD_NAME", fault.code());
        assertTrue(fault.message().contains("bad name!"), fault.message());
        assertEquals(0, bots.size());
    }

    /* A bot that connects and says nothing is told what was expected, and then dropped. */
    @Test
    void aBotThatNeverSaysHelloIsDroppedAfterTheTimeout() throws Exception {
        server.stop();
        server = new BotLinkServer(catalog, bots, mapper, timers, 0, 1_000, Set.of(), "", meters, 200);
        server.start();

        Socket bot = dial();

        Messages.Fault fault = assertInstanceOf(Messages.Fault.class, read(bot));

        assertEquals("HELLO_EXPECTED", fault.code());
        assertTrue(fault.message().contains("200ms"), fault.message());
        assertThrows(EOFException.class, () -> read(bot));
        assertEquals(0, bots.size());
    }

    @Test
    void aSecondHelloOnALiveLinkIsAViolation() throws Exception {
        Socket bot = dial();
        send(bot, hello("alice", "fabric", List.of()));
        assertInstanceOf(Messages.HelloOk.class, read(bot));
        awaitSession("alice");

        send(bot, hello("bob", "fabric", List.of()));

        assertEquals("HELLO_TWICE", assertInstanceOf(Messages.Fault.class, read(bot)).code());
    }

    /* A link that dies takes its session with it, or list-bots reports a bot that cannot answer. */
    @Test
    void aBotThatDropsItsLinkLeavesTheRegistry() throws Exception {
        Socket bot = dial();
        send(bot, hello("alice", "fabric", List.of()));
        assertInstanceOf(Messages.HelloOk.class, read(bot));
        awaitSession("alice");

        bot.close();

        for (int attempt = 0; attempt < 100 && bots.size() > 0; attempt++) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertEquals(0, bots.size());
    }

    /*
    Jackson 3 throws unchecked, so a message the server cannot read used to leave the exception on
    the reader thread: the link closed silently, the bot reconnected, and sent the same thing
    again. It is a breach like any other, and the bot has to be told which one.
    */
    @Test
    void aMessageTheServerCannotReadIsAViolationRatherThanADeadThread() throws Exception {
        Socket bot = dial();
        send(bot, hello("alice", "fabric", List.of()));
        assertInstanceOf(Messages.HelloOk.class, read(bot));
        awaitSession("alice");

        // position is an object; a bot sending a string there disagrees with the contract.
        FrameCodec.write(bot.getOutputStream(), new Frame.Json(
                ("{\"t\":\"status\",\"state\":\"ready\",\"ts\":1,"
                        + "\"position\":\"somewhere near spawn\"}").getBytes(StandardCharsets.UTF_8)));

        assertEquals("MALFORMED_JSON", assertInstanceOf(Messages.Fault.class, read(bot)).code());

        for (int attempt = 0; attempt < 100 && bots.size() > 0; attempt++) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertEquals(0, bots.size(), "a link that broke the contract does not stay in the registry");
    }

    @Test
    void eventsFromABotReachTheFeedItsReadingToolsDrawFrom() throws Exception {
        Socket bot = dial();
        send(bot, hello("alice", "fabric", List.of()));
        assertInstanceOf(Messages.HelloOk.class, read(bot));
        BotSession session = awaitSession("alice");

        long now = System.currentTimeMillis();
        send(bot, new Messages.Event(1, "chat", "server", "Welcome to the server",
                List.of(), null, null, now, now, 1, false));

        for (int attempt = 0; attempt < 100 && session.feed("chat").latest() == null; attempt++) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertEquals("Welcome to the server", session.feed("chat").latest().text());
        assertNull(session.feed("actionBar").latest());
    }
}
