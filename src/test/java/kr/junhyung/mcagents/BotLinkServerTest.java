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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

    private Catalog catalog;
    private BotRegistry bots;
    private BotLinkServer server;

    @BeforeEach
    void start() {
        catalog = Catalog.load();
        bots = new BotRegistry(4);
        server = new BotLinkServer(catalog, bots, mapper, timers, 0);
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
        return new Messages.Hello(List.of(catalog.protocol()), name, kind, "0.1.0", "26.2",
                catalog.version(), capabilities, List.of("blob", "eventFold"));
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
        send(bot, hello("alice", "mineflayer", everything("mineflayer")));

        Messages.HelloOk ok = assertInstanceOf(Messages.HelloOk.class, read(bot));

        assertEquals(catalog.protocol(), ok.protocol());
        assertFalse(ok.acceptedTools().isEmpty());
        assertTrue(ok.rejectedTools().isEmpty(), () -> "unexpectedly rejected: " + ok.rejectedTools());

        BotSession session = awaitSession("alice");
        assertEquals("mineflayer", session.kind());
        assertEquals(ok.acceptedTools().size(), session.capabilityCount());
    }

    /*
    The bot is newer than the server here, which is the ordinary state of a rolling update. Making
    that fatal would mean a bot could never ship a tool before the server had heard of it.
    */
    @Test
    void aToolTheCatalogueDoesNotHaveIsIgnoredRatherThanRejected() throws Exception {
        Socket bot = dial();
        send(bot, hello("alice", "mineflayer",
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
        List<Messages.Capability> reported = new ArrayList<>(everything("mineflayer"));
        Messages.Capability first = reported.get(0);
        reported.set(0, new Messages.Capability(first.tool(), "a-hash-from-another-build"));

        Socket bot = dial();
        send(bot, hello("alice", "mineflayer", reported));

        Messages.HelloOk ok = assertInstanceOf(Messages.HelloOk.class, read(bot));

        assertEquals(1, ok.rejectedTools().size());
        assertEquals(first.tool(), ok.rejectedTools().get(0).tool());
        assertFalse(awaitSession("alice").supports(first.tool()));
        assertEquals(reported.size() - 1, ok.acceptedTools().size());
    }

    @Test
    void aBotSpeakingAnotherProtocolIsToldSoAndClosed() throws Exception {
        Socket bot = dial();
        send(bot, new Messages.Hello(List.of(catalog.protocol() + 99), "alice", "mineflayer",
                "0.1.0", "26.2", catalog.version(), List.of(), List.of()));

        Messages.Fault fault = assertInstanceOf(Messages.Fault.class, read(bot));

        assertEquals("PROTOCOL_UNSUPPORTED", fault.code());
        assertThrows(EOFException.class, () -> read(bot));
        assertEquals(0, bots.size());
    }

    @Test
    void aSecondBotUnderTheSameNameIsTurnedAwayAndTheFirstKeepsWorking() throws Exception {
        Socket first = dial();
        send(first, hello("alice", "mineflayer", List.of()));
        assertInstanceOf(Messages.HelloOk.class, read(first));
        awaitSession("alice");

        Socket second = dial();
        send(second, hello("alice", "fabric", List.of()));

        assertEquals("NAME_TAKEN", assertInstanceOf(Messages.Fault.class, read(second)).code());
        assertEquals(1, bots.size());
        assertEquals("mineflayer", bots.resolve("alice").kind());
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
    void aSecondHelloOnALiveLinkIsAViolation() throws Exception {
        Socket bot = dial();
        send(bot, hello("alice", "mineflayer", List.of()));
        assertInstanceOf(Messages.HelloOk.class, read(bot));
        awaitSession("alice");

        send(bot, hello("bob", "mineflayer", List.of()));

        assertEquals("HELLO_TWICE", assertInstanceOf(Messages.Fault.class, read(bot)).code());
    }

    /* A link that dies takes its session with it, or list-bots reports a bot that cannot answer. */
    @Test
    void aBotThatDropsItsLinkLeavesTheRegistry() throws Exception {
        Socket bot = dial();
        send(bot, hello("alice", "mineflayer", List.of()));
        assertInstanceOf(Messages.HelloOk.class, read(bot));
        awaitSession("alice");

        bot.close();

        for (int attempt = 0; attempt < 100 && bots.size() > 0; attempt++) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertEquals(0, bots.size());
    }

    @Test
    void eventsFromABotReachTheFeedItsReadingToolsDrawFrom() throws Exception {
        Socket bot = dial();
        send(bot, hello("alice", "mineflayer", List.of()));
        assertInstanceOf(Messages.HelloOk.class, read(bot));
        BotSession session = awaitSession("alice");

        long now = System.currentTimeMillis();
        send(bot, new Messages.Event(1, "chat", "server", "Welcome to the server",
                List.of(), null, now, now, 1, false));

        for (int attempt = 0; attempt < 100 && session.feed("chat").latest() == null; attempt++) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertEquals("Welcome to the server", session.feed("chat").latest().text());
        assertNull(session.feed("actionBar").latest());
    }
}
