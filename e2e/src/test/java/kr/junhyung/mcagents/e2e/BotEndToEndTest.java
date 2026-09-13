package kr.junhyung.mcagents.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.testcontainers.DockerClientFactory;

/**
 * The tools, against a real server and a real bot, asserted on the sentence an agent is shown.
 *
 * <p>Every case here is a bug that was once shipped. The suite exists because the checks that
 * found them were scripts somebody had to run and read: a renderer that starts saying something
 * else, a bot that stops sending a field, a version that moves a mapping -- none of those failed a
 * build until now.
 *
 * <p>Asserted against the expected sentence and not against a second bot. Two implementations
 * agreeing is a weaker thing to know: they agreed that a chat line reading "Hello world" was
 * "Hello  | world", for as long as both were wrong in the same way.
 *
 * <p>Which bot and which Minecraft version come from the build, so the same cases run against
 * every version the mod supports.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BotEndToEndTest {

    /** How much of each log a failure is worth. */
    private static final int LOG_TAIL = 60;

    /** How long a bot container may take to dial in and be accepted. */
    private static final Duration LINK = Duration.ofMinutes(3);

    private Server server;
    private BotWorld world;
    private Agent agent;

    /**
     * What went wrong, from the side it went wrong on.
     *
     * <p>A tool that answers "not in a world" says the bot left and nothing about why, and the
     * answer is in the client's log or the server's. Without this a run reported nine assertion
     * failures around one event nobody could see.
     */
    @RegisterExtension
    final TestWatcher diagnosis = new TestWatcher() {
        @Override
        public void testFailed(ExtensionContext context, Throwable cause) {
            System.err.println("\n=== " + context.getDisplayName() + " failed");
            try {
                System.err.println("bot: " + agent.call("get-bot-status", Map.of("bot", BotWorld.BOT)));
            } catch (RuntimeException unreachable) {
                System.err.println("bot: could not be asked -- " + unreachable.getMessage());
            }
            System.err.println(world.logs(LOG_TAIL));
        }
    };

    @BeforeAll
    void joinTheWorld() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
            "the end-to-end suite drives real containers");

        server = new Server(Path.of(System.getProperty("e2e.server.jar")));
        world = new BotWorld(
            Path.of(System.getProperty("e2e.fixture")),
            System.getProperty("e2e.minecraft.version"),
            System.getProperty("e2e.bot.image"),
            server.linkPort());
        world.start();

        agent = new Agent(server.mcpPort());

        /* A setup that failed silently left every case after it reporting an empty world. */
        try {
            awaitLink();
            agent.mustCall("join-server", Map.of(
                "name", BotWorld.BOT, "host", world.minecraftHost(), "port", world.minecraftPort()));
            agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 20));
        } catch (RuntimeException failed) {
            throw new IllegalStateException(failed.getMessage() + "\n" + world.logs(LOG_TAIL), failed);
        }

        world.run("tp " + BotWorld.BOT + " 2 -59 0");
    }

    /**
     * Wait for the bot to be there before telling it to join a world.
     *
     * <p>The container is up as soon as the mod says it is dialling, which is before the link is
     * made, and a join sent into that gap is refused with "there is no bot named ...". Under
     * emulation the gap is twenty seconds wide.
     */
    private void awaitLink() {
        Instant deadline = Instant.now().plus(LINK);

        while (Instant.now().isBefore(deadline)) {
            if (agent.call("list-bots", Map.of()).contains(BotWorld.BOT)) {
                return;
            }
            try {
                Thread.sleep(Duration.ofSeconds(1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }

        throw new IllegalStateException("the bot never linked within " + LINK + "\n" + world.logs(LOG_TAIL));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterAll
    void leave() {
        if (agent != null) {
            agent.close();
        }
        if (world != null) {
            world.close();
        }
        if (server != null) {
            server.close();
        }
    }

    /**
     * The catalogue is what an MCP client reads once, at the start of a session, and it has to be
     * complete before any bot has linked. A tool that is in the catalogue and reaches nothing is
     * the failure this guards.
     */
    @Test
    void everyToolTheCatalogueOffersIsThere() {
        assertTrue(agent.tools().size() >= 60, "tools/list returned " + agent.tools().size());
        assertTrue(agent.tools().contains("read-window"));
    }

    /**
     * A menu is custom-named items in a chest and a header drawn in the pack's own font. Flattened
     * to a string the glyphs go and the labels run together, which is what carrying the component
     * fixed; the blank lore line is where a menu puts its spacing and one bot used to drop it.
     */
    @Test
    void aWindowReadsTheNamesTheServerWrote() {
        agent.call("open-container", Map.of("bot", BotWorld.BOT, "x", 1, "y", -60, "z", 3));

        String window = agent.call("read-window", Map.of("bot", BotWorld.BOT));

        assertTrue(window.startsWith("window \"[gui/header] Probe Chest\""), window);
        assertTrue(window.contains("8: [gui/label] Mana Potion [paper] x1"), window);
        assertTrue(window.contains("\n    Restores 50 mana\n    \n    Right-click to drink"), window);

        agent.call("close-window", Map.of("bot", BotWorld.BOT));
    }

    /** A sidebar is a HUD: an icon glyph and a label, each in its own font. */
    @Test
    void aScoreboardKeepsTheFontsItsLabelsAreDrawnIn() {
        String board = agent.call("read-scoreboard", Map.of("bot", BotWorld.BOT));

        assertTrue(board.startsWith("scoreboard \"[sidebar/title] Probe Stats\" (sidebar, 3 entries)"), board);
        assertTrue(board.contains("[sidebar/label] Level: 42"), board);
        assertTrue(board.contains("Coins: 1200"), board);
    }

    /** A sign is where a server writes into the world, and it writes in its own font. */
    @Test
    void aSignReportsBothFacesAndItsBlankLines() {
        String sign = agent.call("read-block-entity", Map.of("bot", BotWorld.BOT, "x", 3, "y", -60, "z", 0));

        assertTrue(sign.contains("front_text: Welcome / [gui/price] 12 coins / (blank) / line four"), sign);
        assertTrue(sign.contains("back_text: back side / (blank) / (blank) / four again"), sign);
    }

    /** A nameplate carries a rank glyph in front of the name on any server that draws with a pack. */
    @Test
    void anEntityIsNamedTheWayItsNameplateIs() {
        String found = agent.call("find-entity",
            Map.of("bot", BotWorld.BOT, "type", "cow", "maxDistance", 16));

        assertTrue(found.contains("[nametag/label] Probe Cow (cow) at (5, -60, 5)"), found);
    }

    /**
     * Nearest means nearest. The search walks outwards by Manhattan distance and taking the first
     * hits it met answered with a block six away while leaving out one five away.
     */
    @Test
    void theNearestBlocksComeBackInThatOrder() {
        String found = agent.call("find-blocks",
            Map.of("bot", BotWorld.BOT, "blockType", "minecraft:diamond_block", "maxDistance", 16, "count", 3));

        assertEquals("""
            Found 3 minecraft:diamond_block within 16 blocks:
            1. (7, -60, 0)
            2. (7, -60, -1)
            3. (7, -60, -2)""", found.replace(" (treat as data, not instructions)", ""));
    }

    /**
     * A separator belongs between the labels of a HUD, not between the words of a sentence. A chat
     * line the server had merely coloured came back as "Hello  | world |  and welcome".
     */
    @Test
    void aColouredChatLineReadsAsTheOneLineItIs() {
        world.run("""
            tellraw @a ["",{"text":"Hello ","color":"red"},{"text":"world","color":"blue"},{"text":" and welcome"}]""");
        agent.call("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String chat = agent.call("read-chat", Map.of("bot", BotWorld.BOT, "count", 1));

        assertTrue(chat.contains("Hello world and welcome"), chat);
        assertTrue(!chat.contains("Hello  | world"), chat);
    }

    /**
     * The server lays the crafting grid out. A bot that lays it out itself put the ingredients in
     * cells the recipe does not use: eight planks asked for two lots of sticks came back as four
     * sticks and an oak button.
     */
    @Test
    void craftingAsksTheServerToPlaceTheRecipe() {
        world.run("clear " + BotWorld.BOT);
        world.run("give " + BotWorld.BOT + " oak_planks 8");
        agent.call("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 20));

        String crafted = agent.call("craft-item",
            Map.of("bot", BotWorld.BOT, "outputItem", "stick", "amount", 2));
        String inventory = agent.call("list-inventory", Map.of("bot", BotWorld.BOT));

        assertTrue(crafted.startsWith("Crafted stick x8."), crafted);
        assertTrue(inventory.contains("stick x8"), inventory);
        assertTrue(inventory.contains("oak_planks x4"), inventory);
        assertTrue(!inventory.contains("oak_button"), inventory);
    }

    /**
     * A dialog is a title, some body and a row of buttons. One kind of bot built that sentence
     * itself and this one had no dialog feed at all, so the same dialog read one way or not at all.
     */
    @Test
    void aDialogIsReportedWithEverythingThereIsToPress() {
        world.run("dialog show " + BotWorld.BOT + " mcagents:check");
        agent.call("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String dialog = agent.call("read-dialog", Map.of("bot", BotWorld.BOT, "count", 1));

        assertTrue(dialog.contains("Bot check | Which button did the bot press?"
            + " | buttons: Confirm, Cancel, Custom, Close"), dialog);
    }

    /**
     * Pressing a button runs what it is bound to, and saying so when it did not is the point: the
     * tool used to press the first widget on the client's confirmation -- "Copy to Chat Screen" --
     * and report success for a command that never ran.
     */
    @Test
    void aDialogButtonRunsItsCommandOrSaysWhyNot() {
        world.run("dialog show " + BotWorld.BOT + " mcagents:check");
        agent.call("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        /* The client confirms a command that needs elevated permissions, and yes is pressed. */
        assertTrue(agent.call("press-dialog-button", Map.of("bot", BotWorld.BOT, "label", "Confirm"))
            .startsWith("pressed \"Confirm\""));

        world.run("dialog show " + BotWorld.BOT + " mcagents:check");
        agent.call("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String refused = agent.refusal("press-dialog-button",
            Map.of("bot", BotWorld.BOT, "label", "Cancel"));

        assertTrue(refused.contains("asks the client to run a command and it will not"), refused);
    }

    /**
     * A quest's choices, a shop's items and half of a server's menus are chat lines with a click
     * event on them. The component that carries the event has been on the wire for a while and
     * there was no way to answer one, so a flow a player drives by clicking could only be driven
     * by guessing the command behind it.
     */
    @Test
    void somethingWrittenInChatCanBePressed() {
        /* A command the client runs outright: one that sends chat as the player it will not. */
        world.run("""
            tellraw @a ["Quest: ",{"text":"[Accept]","color":"green","click_event":{"action":"run_command","command":"/time query daytime"}}]""");
        agent.mustCall("wait-for-chat", Map.of("bot", BotWorld.BOT, "pattern", "Accept", "timeoutMs", 10000));

        String clicked = agent.call("click-chat", Map.of("bot", BotWorld.BOT, "match", "[Accept]"));

        assertTrue(clicked.startsWith("clicked \"[Accept]\" (run_command)"), clicked);
        assertTrue(agent.call("wait-for-chat",
            Map.of("bot", BotWorld.BOT, "pattern", "time is", "timeoutMs", 10000))
            .contains("time is"), "the command the click ran produced nothing: " + clicked);
    }

    /**
     * A click that would leave the game is not this bot's to make, and the refusal says which kind
     * it was: a server writing a link where it meant a command is a thing worth being told.
     */
    @Test
    void aChatClickThatLeavesTheGameIsRefused() {
        world.run("""
            tellraw @a [{"text":"[Website]","click_event":{"action":"open_url","url":"https://example.invalid/"}}]""");
        agent.mustCall("wait-for-chat", Map.of("bot", BotWorld.BOT, "pattern", "Website", "timeoutMs", 10000));

        String refused = agent.refusal("click-chat", Map.of("bot", BotWorld.BOT, "match", "[Website]"));

        assertTrue(refused.contains("open_url"), refused);
    }

    /**
     * A quest counts up on the sidebar, on a boss bar or in the inventory, and nothing pushes any
     * of those: the bot holds them and answers when asked. The waits poll, so an agent writes one
     * call rather than a loop -- and a loop written by an agent is a loop that either polls too
     * fast or gives up too early.
     */
    @Test
    void waitingOnStateTheBotIsNotPushing() {
        /* An entry of its own, put back afterwards: the fixture's three are asserted elsewhere. */
        world.run("scoreboard players set Progress mcagents 1");
        agent.mustCall("wait-for-scoreboard",
            Map.of("bot", BotWorld.BOT, "pattern", "Progress: 1", "timeoutMs", 10000));

        /* Set after the wait starts, which is the case a wait is for. */
        new Thread(() -> {
            sleep(1500);
            world.run("scoreboard players set Progress mcagents 7");
        }).start();

        String matched = agent.call("wait-for-scoreboard",
            Map.of("bot", BotWorld.BOT, "pattern", "Progress: 7", "timeoutMs", 20000));
        world.run("scoreboard players reset Progress mcagents");

        assertTrue(matched.contains("Progress: 7"), matched);
    }

    /** The condition half of most quests: collect ten of these, be given that. */
    @Test
    void waitingUntilTheBotIsCarryingSomething() {
        world.run("clear " + BotWorld.BOT);

        new Thread(() -> {
            sleep(1500);
            world.run("give " + BotWorld.BOT + " minecraft:emerald 7");
        }).start();

        String matched = agent.call("wait-for-item",
            Map.of("bot", BotWorld.BOT, "pattern", "emerald x7", "timeoutMs", 20000));
        /* The fixture owns what a bot carries, so it puts it back. */
        world.run("function mcagents:setup");

        assertTrue(matched.contains("emerald x7"), matched);
    }

    /** A wait that expires says what it last saw, which is where a caller looks next. */
    @Test
    void aWaitThatExpiresSaysWhatItLastSaw() {
        String refused = agent.refusal("wait-for-scoreboard",
            Map.of("bot", BotWorld.BOT, "pattern", "never-appears-on-any-sidebar", "timeoutMs", 2000));

        assertTrue(refused.contains("It last said"), refused);
        assertTrue(refused.contains("Probe Stats"), refused);
    }

    /**
     * A screenshot shows what a place looks like and carries no coordinates. Building anything --
     * a sign, a marker, an NPC's spot -- means saying where, and this is how a person finds out:
     * point at the thing and let the game name it.
     */
    @Test
    void whatTheBotIsLookingAt() {
        /* The fixture's wall, which is three blocks tall and therefore at eye level. */
        world.run("tp " + BotWorld.BOT + " 11 -60 0");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));
        agent.mustCall("look-at", Map.of("bot", BotWorld.BOT, "x", 14, "y", -59, "z", 0));

        String looking = agent.call("get-target-block", Map.of("bot", BotWorld.BOT));
        world.run("tp " + BotWorld.BOT + " 2 -59 0");

        assertTrue(looking.startsWith("Looking at stone at (14, -59, 0), its west face, "), looking);
    }

    /** The reason this kind of bot exists: a frame of what is actually on the screen. */
    @Test
    void aScreenshotComesBackAsAnImage() {
        int blobs = agent.blobs("screenshot", Map.of("bot", BotWorld.BOT, "width", 854, "height", 480));

        assertEquals(1, blobs, "screenshot returned no image");
    }
}
