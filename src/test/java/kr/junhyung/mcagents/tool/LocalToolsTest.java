package kr.junhyung.mcagents.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.junhyung.mcagents.bot.BotLink;
import kr.junhyung.mcagents.bot.BotProvisioner;
import kr.junhyung.mcagents.bot.BotRegistry;
import kr.junhyung.mcagents.bot.BotSession;
import kr.junhyung.mcagents.catalog.Catalog;
import kr.junhyung.mcagents.catalog.ToolSpec;
import kr.junhyung.mcagents.protocol.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tools the server answers alone, through the dispatcher an agent's call goes through: the
 * refusal and the default an agent meets are what these hold.
 */
class LocalToolsTest {

    private static final Pattern ADVERTISED_DEFAULT = Pattern.compile("\\(default: (\\d+)\\)");

    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    private final List<AutoCloseable> open = new ArrayList<>();
    private final Catalog catalog = Catalog.load();
    private final BotRegistry bots = new BotRegistry(8);
    private final ToolDispatcher dispatcher = new ToolDispatcher(bots, new LocalTools(bots), new RemoteTools(catalog),
            new Orchestration(bots, new BotProvisioner(null, null, null, 0, null)));

    @AfterEach
    void stop() throws Exception {
        for (AutoCloseable closeable : open) {
            closeable.close();
        }
        timers.shutdownNow();
    }

    /* A session always has a link, so it gets a real loopback socket pair. */
    private BotSession linked(String name, String kind) throws IOException {
        ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Socket botSide = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
        Socket serverSide = listener.accept();
        open.add(listener);
        open.add(botSide);

        BotSession session = new BotSession(name, kind, new BotLink(serverSide, JsonMapper.builder().build(), timers), timers);
        bots.add(session);
        return session;
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    /**
     * A feed only one kind of bot fills is empty on the other kind for good, and reading it there
     * answered "the server has not sent a sound or particle yet" -- a false negative in the words of
     * a fact. The refusal is the one every tool a kind cannot run gets, before anything is read.
     */
    @Test
    void aFeedTheKindNeverFillsIsRefusedByNameRatherThanReadAsEmpty() throws IOException {
        linked("az", "azalea");

        McpSchema.CallToolResult read = dispatcher.call(catalog.require("read-effects"), Map.of("bot", "az"));
        McpSchema.CallToolResult waited = dispatcher.call(catalog.require("wait-for-effect"),
                Map.of("bot", "az", "pattern", "pling", "timeoutMs", 100));

        assertTrue(read.isError(), text(read));
        assertTrue(text(read).contains("\"read-effects\" is not supported by bot \"az\" (kind: azalea)"), text(read));
        assertTrue(text(read).contains("Bots of kind fabric support it"), text(read));
        assertTrue(waited.isError(), text(waited));
        assertTrue(text(waited).contains("\"wait-for-effect\" is not supported by bot \"az\" (kind: azalea)"), text(waited));
    }

    @Test
    void aFeedEveryKindFillsIsStillReadOnEither() throws IOException {
        linked("az", "azalea");

        McpSchema.CallToolResult read = dispatcher.call(catalog.require("read-chat"), Map.of("bot", "az"));

        assertFalse(read.isError(), text(read));
        assertEquals("The server has not sent a chat line yet.", text(read));
    }

    /**
     * The catalogue's "(default: N)" is the only place an agent learns what leaving count out
     * means, and read-chat said 20 while handing back 5: the plugin line that explained a failure
     * was the sixth one back and never shown. Every reading tool is held to the number it advertises.
     */
    @Test
    void aReadWithoutACountShowsAsManyLinesAsTheCatalogueSaysItWill() throws IOException {
        BotSession bot = linked("fab", "fabric");
        int checked = 0;

        for (ToolSpec spec : catalog.all()) {
            if (spec.route() != ToolSpec.Route.LOCAL || !spec.name().startsWith("read-") || feedOf(spec) == null) {
                continue;
            }
            int advertised = advertisedDefault(spec, "count");
            String feed = feedOf(spec);

            for (int seq = 1; seq <= advertised + 5; seq++) {
                bot.accept(new Messages.Event(seq, feed, sourceOf(feed), feed + " line " + seq, List.of(), null, null,
                        seq, seq, 1, false));
            }

            String shown = text(dispatcher.call(spec, Map.of("bot", "fab")));

            assertEquals(advertised, shown.split("\n").length,
                    spec.name() + " advertises " + advertised + " and showed:\n" + shown);
            checked++;
        }
        assertEquals(6, checked, "the feed-reading tools were not all found");
    }

    /**
     * The same, for the timeout ping-server fills in: the catalogue said 3000 while the code waited
     * 5000. The failure names the timeout it used, so a ping at a port nothing listens on says which.
     */
    @Test
    void aPingWithoutATimeoutWaitsAsLongAsTheCatalogueSaysItWill() throws IOException {
        ToolSpec spec = catalog.require("ping-server");
        int port;
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = taken.getLocalPort();
        }

        String refused = text(dispatcher.call(spec, Map.of("host", "127.0.0.1", "port", port)));

        assertTrue(refused.contains("within " + advertisedDefault(spec, "timeoutMs") + "ms"), refused);
    }

    /** wait-for-server fills its timeout from the deadline the catalogue gives the call, not a number of its own. */
    @Test
    void waitForServersAdvertisedTimeoutIsTheDeadlineItReallyUses() {
        ToolSpec spec = catalog.require("wait-for-server");

        assertEquals(spec.defaultDeadlineMs(), advertisedDefault(spec, "timeoutMs"));
    }

    @SuppressWarnings("unchecked")
    private static int advertisedDefault(ToolSpec spec, String property) {
        Map<String, Object> properties = (Map<String, Object>) spec.inputSchema().get("properties");
        String description = (String) ((Map<String, Object>) properties.get(property)).get("description");
        Matcher stated = ADVERTISED_DEFAULT.matcher(description);

        assertTrue(stated.find(), spec.name() + "." + property + " does not state a default: " + description);

        return Integer.parseInt(stated.group(1));
    }

    private static String feedOf(ToolSpec spec) {
        return switch (spec.name()) {
            case "read-chat" -> "chat";
            case "read-action-bar" -> "actionBar";
            case "read-title" -> "title";
            case "read-dialog" -> "dialog";
            case "read-effects" -> "effect";
            case "read-toasts" -> "toast";
            default -> null;
        };
    }

    private static String sourceOf(String feed) {
        return switch (feed) {
            case "chat" -> "system";
            case "actionBar" -> "actionbar";
            case "effect" -> "sound";
            default -> feed;
        };
    }
}
