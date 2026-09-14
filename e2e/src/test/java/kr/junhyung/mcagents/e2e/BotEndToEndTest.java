package kr.junhyung.mcagents.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
            Path.of(System.getProperty("e2e.fixture.plugin")),
            System.getProperty("e2e.minecraft.version"),
            System.getProperty("e2e.bot.image"),
            System.getProperty("e2e.bot.kind"),
            server.linkPort());
        world.start();

        agent = new Agent(server.mcpPort(), System.getProperty("e2e.bot.kind"));

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
     * Every case starts from the same place, in the same mode, with nothing open.
     *
     * <p>Cases leave the bot where they finished: beside a villager, behind a sign, in survival,
     * with a window up. Three times a case failed only because of the one that ran before it -- a
     * sign written on its back, a thrown stack picked back up, a villager out of reach -- and each
     * was fixed by teaching that one case to stand somewhere. This is that, once, for all of them.
     */
    @BeforeEach
    void standWhereEveryCaseStarts() {
        if (agent.runs("close-window")) {
            agent.call("close-window", Map.of("bot", BotWorld.BOT));
        }
        world.run("dialog clear " + BotWorld.BOT);
        world.run("gamemode creative " + BotWorld.BOT);
        /*
        In the overworld: a plain tp moves a bot within whatever dimension a case left it in. And
        facing south and level, because a plain tp keeps the rotation too: a case that walked to the
        enchanting table left the bot looking at it, and the next case's right-click opened the table
        instead of casting the rod it held.
        */
        world.run("execute in minecraft:overworld run tp " + BotWorld.BOT + " 2 -59 0 0 0");
        agent.call("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));
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
        /* Nearest is measured from the bot, and a case before this one may have walked it somewhere. */
        world.run("tp " + BotWorld.BOT + " 2 -59 0");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));

        String found = agent.call("find-blocks",
            Map.of("bot", BotWorld.BOT, "blockType", "minecraft:diamond_block", "maxDistance", 16, "count", 3));

        assertEquals("""
            Found 3 minecraft:diamond_block within 16 blocks:
            1. (7, -60, 0)
            2. (7, -60, -1)
            3. (7, -60, -2)""", found.replace(" (treat as data, not instructions)", ""));
    }

    /**
     * A block name nothing is called. The lookup took the registry's default for a name it did not
     * know, which is air, so a typo answered with every empty block within range.
     */
    @Test
    void aBlockNobodyIsCalledIsRefusedRatherThanReadAsAir() {
        String refused = agent.refusal("find-blocks",
            Map.of("bot", BotWorld.BOT, "blockType", "diamond_blok", "maxDistance", 8, "count", 3));

        assertTrue(refused.contains("there is no block called diamond_blok"), refused);
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
            tellraw @a ["Quest: ",{"text":"[Accept]","color":"green","click_event":{"action":"run_command","command":"/difficulty"}}]""");
        agent.mustCall("wait-for-chat", Map.of("bot", BotWorld.BOT, "pattern", "Accept", "timeoutMs", 10000));

        String clicked = agent.call("click-chat", Map.of("bot", BotWorld.BOT, "match", "[Accept]"));

        assertTrue(clicked.startsWith("clicked \"[Accept]\" (run_command)"), clicked);
        assertTrue(agent.mustCall("wait-for-chat",
            Map.of("bot", BotWorld.BOT, "pattern", "difficulty is", "timeoutMs", 10000))
            .contains("difficulty is"), "the command the click ran produced nothing: " + clicked);
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

        String matched = agent.mustCall("wait-for-scoreboard",
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

        String matched = agent.mustCall("wait-for-item",
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

    /**
     * Before anything is set, a dialog's inputs read as what the dialog starts them at. One kind of
     * bot reads the dialog out of NBT, where a boolean is a byte and a list of one is a bare compound,
     * so the same definition has to read the same from both.
     */
    @Test
    void aDialogsInputsAreReadAsTheyStart() {
        world.run("dialog show " + BotWorld.BOT + " mcagents:settings");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String read = agent.mustCall("read-dialog", Map.of("bot", BotWorld.BOT, "count", 1));

        assertTrue(read.contains("Probe settings | A checkbox, a cycle and a slider, sent together by one button."
            + " | buttons: Save, Close | inputs: notify (checkbox) = false, mode (one of slow, fast, turbo) = slow,"
            + " speed (0 to 10, step 2) = 4"), read);
    }

    /**
     * A dialog's checkbox, cycle and slider hold what its button sends, and nothing in the packet
     * that opened it says what they hold. The server's scoreboard is the proof: the button scores the
     * slider under a name made of the other two, so each value is asserted as the server got it. The
     * slider is asked for a number between its steps, and the button sends the step it moved to.
     */
    @Test
    void whatIsSetOnADialogsControlsIsWhatTheServerGets() {
        world.run("scoreboard players reset fast_yes mcagents");
        world.run("dialog show " + BotWorld.BOT + " mcagents:settings");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String ticked = agent.mustCall("set-dialog-input", Map.of("bot", BotWorld.BOT, "key", "notify", "value", true));
        String picked = agent.mustCall("set-dialog-input", Map.of("bot", BotWorld.BOT, "key", "mode", "value", "Fast"));
        String slid = agent.mustCall("set-dialog-input", Map.of("bot", BotWorld.BOT, "key", "speed", "value", 5));
        String read = agent.mustCall("read-dialog", Map.of("bot", BotWorld.BOT, "count", 1));

        agent.mustCall("press-dialog-button", Map.of("bot", BotWorld.BOT, "label", "Save"));
        String board = agent.mustCall("wait-for-scoreboard",
            Map.of("bot", BotWorld.BOT, "pattern", "fast_yes: 6", "timeoutMs", 10000));
        world.run("scoreboard players reset fast_yes mcagents");

        assertEquals("Set \"notify\" to true, was false.", ticked);
        assertEquals("Set \"mode\" to fast (shown as \"Fast\"), was slow.", picked);
        assertEquals("Set \"speed\" to 6, was 4. 5 is not one of the slider's steps, so it moved to the nearest.", slid);
        assertTrue(read.contains("inputs: notify (checkbox) = true, mode (one of slow, fast, turbo) = fast,"
            + " speed (0 to 10, step 2) = 6"), read);
        assertTrue(board.contains("fast_yes: 6"), board);
    }

    /** Each way a value can be wrong for a dialog is refused by name, before anything on it moves. */
    @Test
    void aValueADialogsControlCannotTakeIsRefused() {
        world.run("dialog show " + BotWorld.BOT + " mcagents:settings");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String noKey = agent.refusal("set-dialog-input", Map.of("bot", BotWorld.BOT, "key", "colour", "value", "red"));
        String wrongType = agent.refusal("set-dialog-input", Map.of("bot", BotWorld.BOT, "key", "speed", "value", "fast"));
        String outOfRange = agent.refusal("set-dialog-input", Map.of("bot", BotWorld.BOT, "key", "speed", "value", 11));
        String noOption = agent.refusal("set-dialog-input", Map.of("bot", BotWorld.BOT, "key", "mode", "value", "warp"));
        String untouched = agent.mustCall("read-dialog", Map.of("bot", BotWorld.BOT, "count", 1));

        agent.mustCall("close-window", Map.of("bot", BotWorld.BOT));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));
        String noDialog = agent.refusal("set-dialog-input", Map.of("bot", BotWorld.BOT, "key", "notify", "value", true));

        assertTrue(noKey.contains("no input \"colour\"; its inputs are notify, mode, speed"), noKey);
        assertTrue(wrongType.contains("\"speed\" is a slider and takes a number"), wrongType);
        assertTrue(outOfRange.contains("\"speed\" goes from 0 to 10, and 11 is outside it"), outOfRange);
        assertTrue(noOption.contains("\"mode\" has no option \"warp\""), noOption);
        assertTrue(untouched.contains("speed (0 to 10, step 2) = 4"), untouched);
        assertTrue(noDialog.contains("no dialog is open"), noDialog);
    }

    /**
     * The half of a dialog that pressing a button cannot reach. A dialog's text inputs are not in
     * the packet that opened it -- the feed can say a dialog has two of them and not what they say
     * -- so until something could put a value in one, a dialog that asks a question could be read
     * and never answered. The scoreboard is the proof: what was typed is what the server scored.
     */
    @Test
    void whatIsTypedIntoADialogIsWhatTheServerGets() {
        world.run("scoreboard players reset Snowball mcagents");
        world.run("dialog show " + BotWorld.BOT + " mcagents:name");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        /* The field starts out reading "unnamed", so this also says the old value was cleared. */
        String typed = agent.mustCall("type-text",
            Map.of("bot", BotWorld.BOT, "field", "Name", "text", "Snowball"));

        assertTrue(typed.contains("typed into \"Name\""), typed);
        assertTrue(typed.contains("which now reads \"Snowball\""), typed);

        agent.mustCall("press-dialog-button", Map.of("bot", BotWorld.BOT, "label", "Set"));

        /* mustCall, because a wait that expires answers with the pattern in it and would pass. */
        String board = agent.mustCall("wait-for-scoreboard",
            Map.of("bot", BotWorld.BOT, "pattern", "Snowball: 7", "timeoutMs", 10000));
        world.run("scoreboard players reset Snowball mcagents");

        assertTrue(board.contains("Snowball: 7"), board);
    }

    /**
     * Which field, when there is more than one. A one-line box carries its label as its own
     * message and a multi-line box carries an empty one, so the second label has to be read off
     * the widget beside it -- and a screen where only half the fields can be named by name is one
     * an agent has to guess its way around.
     */
    @Test
    void aScreenWithTwoFieldsNamesBothOfThem() {
        world.run("dialog show " + BotWorld.BOT + " mcagents:name");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String refused = agent.refusal("type-text", Map.of("bot", BotWorld.BOT, "text", "Snowball"));

        agent.mustCall("press-dialog-button", Map.of("bot", BotWorld.BOT, "label", "Close"));

        assertTrue(refused.contains("2 text fields and none was asked for"), refused);
        assertTrue(refused.contains("\"Name\", \"Note\""), refused);
    }

    /**
     * A sign is the one screen with text on it that is not a widget, and its text leaves the client
     * only when the editor closes: typing and stopping there writes a sign nobody else ever sees.
     */
    @Test
    void writingOnASignInTheWorld() {
        /* The editor opens on the face that was clicked, so the bot has to stand in front of it. */
        world.run("tp " + BotWorld.BOT + " 2 -59 0");
        world.run("setblock 2 -60 2 oak_sign[rotation=8]");
        /* An empty hand, or the right-click places what is being held instead of opening the sign. */
        world.run("clear " + BotWorld.BOT);
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        agent.mustCall("activate-block", Map.of("bot", BotWorld.BOT, "x", 2, "y", -60, "z", 2));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String written = agent.call("type-text", Map.of("bot", BotWorld.BOT, "text", "Shop\n\nOpen"));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        /* Read back from the world, which is the only place that says the sign was really sent. */
        String sign = agent.call("read-block-entity", Map.of("bot", BotWorld.BOT, "x", 2, "y", -60, "z", 2));

        world.run("setblock 2 -60 2 air");
        world.run("tp " + BotWorld.BOT + " 2 -59 0");
        world.run("function mcagents:setup");

        assertTrue(written.startsWith("wrote the front of the sign"), written);
        assertTrue(written.contains("\"Shop\" / \"\" / \"Open\" / \"\""), written);
        assertTrue(sign.contains("front_text: Shop / (blank) / Open / (blank)"), sign);
    }

    /**
     * A server with something long to say says it in a book, and the client draws one page at a
     * time. Reading one by screenshotting each page is not reading it: a page is a component, and
     * the font a server drew it in is the half a picture cannot give back.
     */
    @Test
    void everyPageOfABookAtOnce() {
        world.run("clear " + BotWorld.BOT);
        world.run("give " + BotWorld.BOT + " written_book[written_book_content={title:\"Probe Guide\","
            + "author:\"Probe\",pages:[{text:\"Find the shrine.\"},"
            + "{text:\"\",extra:[{text:\"12 coins\",font:\"hyperfarm:gui/price\"}]},"
            + "{text:\"Bring back the relic.\"}]}] 1");
        agent.mustCall("wait-for-item",
            Map.of("bot", BotWorld.BOT, "pattern", "written_book", "timeoutMs", 10000));
        agent.mustCall("equip-item", Map.of("bot", BotWorld.BOT, "itemName", "written_book"));
        agent.mustCall("use-held-item", Map.of("bot", BotWorld.BOT));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String book = agent.call("read-book", Map.of("bot", BotWorld.BOT));

        /* close-window used to look for a chest, and answered "No window was open" in front of this. */
        String closed = agent.mustCall("close-window", Map.of("bot", BotWorld.BOT));
        String after = agent.mustCall("close-window", Map.of("bot", BotWorld.BOT));
        world.run("function mcagents:setup");

        assertTrue(closed.startsWith("Closed the book"), closed);
        assertTrue(after.startsWith("No window was open"), "the book was still open: " + after);
        assertTrue(book.startsWith("\"Probe Guide\" by Probe in hand, 3 pages, open at page 1"), book);
        assertTrue(book.contains("1. Find the shrine."), book);
        assertTrue(book.contains("2. [gui/price] 12 coins"), book);
        assertTrue(book.contains("3. Bring back the relic."), book);
    }

    /**
     * A trading screen keeps its trades on the merchant rather than in a slot, so read-window saw
     * an empty window where a villager had three things to sell. And picking a trade is accepted
     * whether or not it can be made: the slots it fills are the only thing that says which, so
     * those are asserted, and the trade is then made to prove that what was picked is what the
     * server gave.
     */
    @Test
    void aVillagersTradesAndTheOneThatWasPicked() {
        world.run("clear " + BotWorld.BOT);
        world.run("give " + BotWorld.BOT + " emerald 3");
        agent.mustCall("wait-for-item",
            Map.of("bot", BotWorld.BOT, "pattern", "emerald", "timeoutMs", 10000));
        agent.mustCall("interact-entity", Map.of("bot", BotWorld.BOT, "name", "villager"));
        agent.mustCall("wait-for-window",
            Map.of("bot", BotWorld.BOT, "titlePattern", "Probe Librarian", "timeoutMs", 10000));
        /* The trades come in a packet of their own, after the screen. */
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String trades = agent.call("read-trades", Map.of("bot", BotWorld.BOT));
        String soldOut = agent.call("select-trade", Map.of("bot", BotWorld.BOT, "trade", "3"));
        String picked = agent.call("select-trade", Map.of("bot", BotWorld.BOT, "trade", "Map Fragment"));

        agent.mustCall("click-slot", Map.of("bot", BotWorld.BOT, "slot", 2, "shift", true));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        agent.mustCall("close-window", Map.of("bot", BotWorld.BOT));
        String inventory = agent.call("list-inventory", Map.of("bot", BotWorld.BOT));

        world.run("function mcagents:setup");

        assertTrue(trades.startsWith("\"[gui/header] Probe Librarian\" (level 3, 40 xp), 3 trades"), trades);
        assertTrue(trades.contains("1. emerald x3 -> [gui/label] Map Fragment [paper] x1 (0/4 uses)"), trades);
        assertTrue(trades.contains("2. emerald x5 + book x1 -> enchanted_book x1 with mending 1 (0/12 uses)"), trades);
        assertTrue(trades.contains("3. wheat x20 -> emerald x1 (16/16 uses, out of stock)"), trades);

        assertTrue(soldOut.contains("slot 2: empty"), soldOut);
        assertTrue(soldOut.contains("the trade is out of stock"), soldOut);

        assertTrue(picked.contains("slot 0: emerald x3"), picked);
        assertTrue(picked.contains("slot 2: [gui/label] Map Fragment [paper] x1"), picked);
        assertTrue(picked.contains("Take slot 2 with click-slot"), picked);

        assertTrue(inventory.contains("[gui/label] Map Fragment [paper]"), inventory);
        assertTrue(!inventory.contains("emerald"), inventory);
    }

    /**
     * A stonecutter's results are drawn by the screen and sent as a number that means whichever
     * recipe the list happens to put there. Nothing reached them before: they are not slots, and
     * they are not widgets either.
     */
    @Test
    void aStonecutterResultIsChosenByName() {
        world.run("clear " + BotWorld.BOT);
        world.run("give " + BotWorld.BOT + " stone 4");
        agent.mustCall("wait-for-item", Map.of("bot", BotWorld.BOT, "pattern", "stone", "timeoutMs", 10000));
        /* open-container is for blocks that hold items; a menu with no inventory opens like any other block. */
        agent.mustCall("activate-block", Map.of("bot", BotWorld.BOT, "x", -1, "y", -60, "z", 5));
        agent.mustCall("wait-for-window", Map.of("bot", BotWorld.BOT, "timeoutMs", 10000));
        /* The first hotbar slot is 29 on a stonecutter, and a shift-click sends stone to the input. */
        agent.mustCall("click-slot", Map.of("bot", BotWorld.BOT, "slot", 29, "shift", true));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String options = agent.mustCall("read-container-options", Map.of("bot", BotWorld.BOT));
        String ambiguous = agent.refusal("press-container-button",
            Map.of("bot", BotWorld.BOT, "option", "stone brick"));
        String pressed = agent.mustCall("press-container-button",
            Map.of("bot", BotWorld.BOT, "option", "stone bricks"));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        String window = agent.mustCall("read-window", Map.of("bot", BotWorld.BOT));

        agent.call("close-window", Map.of("bot", BotWorld.BOT));
        world.run("function mcagents:setup");

        assertTrue(options.contains("Stone Bricks [stone_bricks], x1"), options);
        assertTrue(ambiguous.contains("\"Stone Brick Slab\""), ambiguous);
        assertTrue(pressed.startsWith("Pressed \"Stone Bricks\""), pressed);
        assertTrue(window.contains("  1: stone_bricks x1"), window);
    }

    /**
     * An enchanting table's offers are three numbers the server pushes into the menu, and which
     * enchantment each is changes with the seed. So the offer is read first and pressed by the name
     * it was read as, and the lapis it cost is what says the server took the press.
     */
    @Test
    void anEnchantmentOfferIsPressedByTheNameItIsShownAs() {
        /*
        Survival, because creative enchants for free: the table takes no lapis from a player with
        infinite materials, and the lapis it takes is how this case knows the server accepted the
        press rather than only that the bot sent one.
        */
        world.run("gamemode survival " + BotWorld.BOT);
        world.run("clear " + BotWorld.BOT);
        world.run("experience set " + BotWorld.BOT + " 30 levels");
        world.run("give " + BotWorld.BOT + " iron_pickaxe 1");
        world.run("give " + BotWorld.BOT + " lapis_lazuli 3");
        agent.mustCall("wait-for-item", Map.of("bot", BotWorld.BOT, "pattern", "lapis", "timeoutMs", 10000));
        /* open-container is for blocks that hold items; a menu with no inventory opens like any other block. */
        agent.mustCall("activate-block", Map.of("bot", BotWorld.BOT, "x", -1, "y", -60, "z", 3));
        agent.mustCall("wait-for-window", Map.of("bot", BotWorld.BOT, "timeoutMs", 10000));
        agent.mustCall("click-slot", Map.of("bot", BotWorld.BOT, "slot", 29, "shift", true));
        agent.mustCall("click-slot", Map.of("bot", BotWorld.BOT, "slot", 30, "shift", true));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String options = agent.mustCall("read-container-options", Map.of("bot", BotWorld.BOT));
        String refused = agent.refusal("press-container-button",
            Map.of("bot", BotWorld.BOT, "option", "Nothing Like It"));
        Matcher first = Pattern.compile("\n  0\\. (.+?) \\[").matcher(options);
        assertTrue(first.find(), options);

        String pressed = agent.mustCall("press-container-button",
            Map.of("bot", BotWorld.BOT, "option", first.group(1)));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        String window = agent.mustCall("read-window", Map.of("bot", BotWorld.BOT));

        agent.call("close-window", Map.of("bot", BotWorld.BOT));
        world.run("experience set " + BotWorld.BOT + " 0 levels");
        world.run("gamemode creative " + BotWorld.BOT);
        world.run("function mcagents:setup");

        assertTrue(options.contains("offers 3 options"), options);
        assertTrue(options.contains("1 lapis") && options.contains("3 lapis"), options);
        assertTrue(refused.contains("it offers \"" + first.group(1) + "\""), refused);
        assertTrue(pressed.startsWith("Pressed \"" + first.group(1) + "\" (button 0)"), pressed);
        assertTrue(window.contains("  1: lapis_lazuli x2"), window);
    }

    /**
     * A lectern is drawn by the book screen, which is not a container screen, so everything that
     * looked for one said nothing was open. Its page turns are buttons the server answers, and the
     * page the screen shows afterwards is the server's answer.
     */
    @Test
    void aLecternTurnsToThePageItIsAskedFor() {
        world.run("clear " + BotWorld.BOT);
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        agent.mustCall("activate-block", Map.of("bot", BotWorld.BOT, "x", 0, "y", -60, "z", 7));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String options = agent.mustCall("read-container-options", Map.of("bot", BotWorld.BOT));
        String pressed = agent.mustCall("press-container-button", Map.of("bot", BotWorld.BOT, "option", "page 3"));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        String book = agent.mustCall("read-book", Map.of("bot", BotWorld.BOT));

        agent.call("press-dialog-button", Map.of("bot", BotWorld.BOT, "label", "Done"));
        world.run("function mcagents:setup");

        assertTrue(options.contains("(type minecraft:lectern) offers 3 options, open at page 1 of 3"), options);
        assertTrue(options.contains("  1. previous page, unavailable"), options);
        assertTrue(pressed.startsWith("Pressed \"page 3\" (button 102)"), pressed);
        assertTrue(book.contains("open at page 3"), book);
    }

    /**
     * A number key sends the Inventory index, not the key and not a window slot: the "1" key is
     * index 0. Sending the key's own number trades with the hotbar slot next door, and the client's
     * prediction shows the swap it asked for either way, so this asks the server where the sword is.
     */
    @Test
    void aNumberKeyTradesWithTheHotbarSlotItNames() {
        world.run("function mcagents:setup");
        agent.mustCall("wait-for-item", Map.of("bot", BotWorld.BOT, "pattern", "diamond x3", "timeoutMs", 10000));
        agent.mustCall("open-container", Map.of("bot", BotWorld.BOT, "x", 1, "y", -60, "z", 3));

        agent.mustCall("click-slot",
            Map.of("bot", BotWorld.BOT, "slot", 0, "mode", "swap-hotbar", "hotbar", 1));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        agent.mustCall("close-window", Map.of("bot", BotWorld.BOT));

        String chest = world.run("data get block 1 -60 3 Items[{Slot:0b}].id");
        String hotbar = world.run("data get entity " + BotWorld.BOT + " Inventory[{Slot:0b}].id");
        world.run("function mcagents:setup");

        assertTrue(chest.contains("minecraft:diamond\""), chest);
        assertTrue(hotbar.contains("minecraft:diamond_sword"), hotbar);
    }

    /**
     * Drop takes 0 for one item and 1 for the stack, the other way round from what "left" and
     * "right" would suggest. Swapped, control-drop leaves eleven in the chest and one on the floor.
     */
    @Test
    void controlDropThrowsTheWholeStack() {
        world.run("function mcagents:setup");
        /*
        Where the stack lands depends on where the bot throws it from. From a spot a case before this
        one left the bot in, it landed within reach, and a player picks up what they threw once two
        seconds have passed -- which on a slow runner was before the ground was looked at.
        */
        world.run("tp " + BotWorld.BOT + " 2 -59 0");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));
        agent.mustCall("open-container", Map.of("bot", BotWorld.BOT, "x", 1, "y", -60, "z", 3));

        agent.mustCall("click-slot", Map.of("bot", BotWorld.BOT, "slot", 4, "mode", "throw-stack"));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        agent.mustCall("close-window", Map.of("bot", BotWorld.BOT));

        String chest = world.run("data get block 1 -60 3 Items[{Slot:4b}]");
        String ground = world.run(
            "execute if entity @e[type=item,nbt={Item:{id:\"minecraft:cooked_beef\",count:12}}]");
        world.run("function mcagents:setup");

        assertTrue(chest.startsWith("Found no elements"), chest);
        assertTrue(ground.startsWith("Test passed"), ground);
    }

    /**
     * A drag's button is the phase and the kind packed into one number, and a wrong packing is not
     * refused anywhere: the menu resets and nothing moves. The client predicts the drag too, so
     * only the server's chest says whether the planks were really shared out.
     */
    @Test
    void aDragSharesTheCursorAcrossTheSlotsTheServerHolds() {
        world.run("function mcagents:setup");
        agent.mustCall("wait-for-item", Map.of("bot", BotWorld.BOT, "pattern", "oak_planks x24", "timeoutMs", 10000));
        agent.mustCall("open-container", Map.of("bot", BotWorld.BOT, "x", 1, "y", -60, "z", 3));

        /* The second hotbar slot, 55 in a single chest: 27 of chest, 27 of inventory, then the hotbar. */
        agent.mustCall("click-slot", Map.of("bot", BotWorld.BOT, "slot", 55));
        agent.mustCall("drag-slots", Map.of("bot", BotWorld.BOT, "slots", List.of(1, 2, 3)));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        agent.mustCall("close-window", Map.of("bot", BotWorld.BOT));

        String first = world.run("data get block 1 -60 3 Items[{Slot:1b}].count");
        String second = world.run("data get block 1 -60 3 Items[{Slot:2b}].count");
        String third = world.run("data get block 1 -60 3 Items[{Slot:3b}].count");
        world.run("function mcagents:setup");

        assertTrue(first.endsWith(": 8"), first);
        assertTrue(second.endsWith(": 8"), second);
        assertTrue(third.endsWith(": 8"), third);
    }

    /**
     * A click answers with the slot and the other stack of a swap as they came out. A client that
     * reports its own guess reads a number key as a window slot, and answered this swap with the
     * sword still in the chest while the server had already traded it for the diamonds.
     */
    @Test
    void aNumberKeySwapIsAnsweredWithWhatTheServerDid() {
        world.run("function mcagents:setup");
        agent.mustCall("wait-for-item", Map.of("bot", BotWorld.BOT, "pattern", "diamond x3", "timeoutMs", 10000));
        agent.mustCall("open-container", Map.of("bot", BotWorld.BOT, "x", 1, "y", -60, "z", 3));

        String awaited = agent.mustCall("wait-for-window",
            Map.of("bot", BotWorld.BOT, "titlePattern", "Probe Chest", "timeoutMs", 2000));
        String clicked = agent.mustCall("click-slot",
            Map.of("bot", BotWorld.BOT, "slot", 0, "mode", "swap-hotbar", "hotbar", 1));
        agent.mustCall("close-window", Map.of("bot", BotWorld.BOT));

        String chest = world.run("data get block 1 -60 3 Items[{Slot:0b}].id");
        world.run("function mcagents:setup");

        assertTrue(awaited.startsWith("window \"[gui/header] Probe Chest\""), awaited);
        assertTrue(clicked.contains("  slot 0: Excalibur [diamond_sword] x1 -> diamond x3"), clicked);
        assertTrue(clicked.contains("  hotbar 1: diamond x3 -> Excalibur [diamond_sword] x1"), clicked);
        assertTrue(chest.contains("minecraft:diamond\""), chest);
    }

    /**
     * A drag answers with what each slot came out holding. Guessed on the client, a drag onto empty
     * slots moved nothing and the planks stayed on the cursor, in an answer about a chest the server
     * had already filled.
     */
    @Test
    void aDragIsAnsweredWithWhatTheServerSharedOut() {
        world.run("function mcagents:setup");
        agent.mustCall("wait-for-item", Map.of("bot", BotWorld.BOT, "pattern", "oak_planks x24", "timeoutMs", 10000));
        agent.mustCall("open-container", Map.of("bot", BotWorld.BOT, "x", 1, "y", -60, "z", 3));

        agent.mustCall("click-slot", Map.of("bot", BotWorld.BOT, "slot", 55));
        String dragged = agent.mustCall("drag-slots", Map.of("bot", BotWorld.BOT, "slots", List.of(1, 2, 3)));
        agent.mustCall("close-window", Map.of("bot", BotWorld.BOT));

        String second = world.run("data get block 1 -60 3 Items[{Slot:2b}].count");
        world.run("function mcagents:setup");

        assertTrue(dragged.contains("  slot 1: empty -> oak_planks x8"), dragged);
        assertTrue(dragged.contains("  slot 3: empty -> oak_planks x8"), dragged);
        assertTrue(dragged.contains("  cursor: oak_planks x24 -> empty"), dragged);
        assertTrue(second.endsWith(": 8"), second);
    }

    /** The cursor is emptied by a click outside the window, and the whole stack goes with it. */
    @Test
    void droppingTheCursorThrowsTheStackItHeld() {
        world.run("function mcagents:setup");
        world.run("tp " + BotWorld.BOT + " 2 -59 0");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));
        agent.mustCall("open-container", Map.of("bot", BotWorld.BOT, "x", 1, "y", -60, "z", 3));

        agent.mustCall("click-slot", Map.of("bot", BotWorld.BOT, "slot", 4));
        String dropped = agent.mustCall("drop-held-item", Map.of("bot", BotWorld.BOT));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        agent.mustCall("close-window", Map.of("bot", BotWorld.BOT));

        String ground = world.run(
            "execute if entity @e[type=item,nbt={Item:{id:\"minecraft:cooked_beef\",count:12}}]");
        world.run("function mcagents:setup");

        assertEquals("Dropped cooked_beef x12 from the cursor.", dropped);
        assertTrue(ground.startsWith("Test passed"), ground);
    }

    /**
     * Equipping is clicks in the inventory window, and only the server says where the item went:
     * the off-hand is a swap with the off-hand key, and a helmet is picked up and put down.
     */
    @Test
    void anEquippedItemIsWhereTheServerWearsIt() {
        world.run("function mcagents:setup");
        world.run("item replace entity " + BotWorld.BOT + " inventory.5 with minecraft:iron_helmet");
        agent.mustCall("wait-for-item", Map.of("bot", BotWorld.BOT, "pattern", "iron_helmet", "timeoutMs", 10000));

        String offhand = agent.mustCall("equip-item",
            Map.of("bot", BotWorld.BOT, "itemName", "bow", "destination", "off-hand"));
        String head = agent.mustCall("equip-item",
            Map.of("bot", BotWorld.BOT, "itemName", "iron_helmet", "destination", "head"));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String held = world.run("execute if items entity " + BotWorld.BOT + " weapon.offhand minecraft:bow");
        String worn = world.run("execute if items entity " + BotWorld.BOT + " armor.head minecraft:iron_helmet");
        world.run("function mcagents:setup");

        assertEquals("Equipped bow to off-hand.", offhand);
        assertEquals("Equipped iron_helmet to head.", head);
        assertTrue(held.startsWith("Test passed"), held);
        assertTrue(worn.startsWith("Test passed"), worn);
    }

    /**
     * The server sends nothing back for a creative stack, so the client puts it in the slot itself.
     * Sent without that, the server held the stack and every read on the bot said the slot was empty.
     * It is found by the name on its tooltip as well as by its id.
     */
    @Test
    void aGivenItemIsInTheSlotTheServerFilledAndReadableAtOnce() {
        world.run("function mcagents:setup");
        agent.mustCall("wait-for-item", Map.of("bot", BotWorld.BOT, "pattern", "diamond x3", "timeoutMs", 10000));

        String put = agent.mustCall("give-item", Map.of("bot", BotWorld.BOT, "itemName", "golden_apple", "count", 2));
        String found = agent.mustCall("find-item", Map.of("bot", BotWorld.BOT, "nameOrType", "Golden Apple"));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        String held = world.run("data get entity " + BotWorld.BOT + " Inventory[{Slot:4b}]");

        world.run("gamemode survival " + BotWorld.BOT);
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));
        String refused = agent.refusal("give-item", Map.of("bot", BotWorld.BOT, "itemName", "golden_apple"));
        world.run("gamemode creative " + BotWorld.BOT);
        world.run("function mcagents:setup");

        assertEquals("Put 2 golden_apple in slot 40.", put);
        assertTrue(found.startsWith("Found golden_apple x2 in slot 40."), found);
        assertTrue(held.contains("minecraft:golden_apple") && held.contains("count: 2"), held);
        assertTrue(refused.contains("The bot is in survival mode; give-item needs creative."), refused);
    }

    /** A swing lands only within reach, so a mob a few blocks off is walked to first. */
    @Test
    void aMobOutOfReachIsWalkedToAndKilled() {
        world.run("tp " + BotWorld.BOT + " 4 -60 -8");
        world.run("kill @e[type=chicken,tag=mcagents]");
        world.run("summon chicken 9 -60 -8 {Tags:[\"mcagents\"],NoAI:1b,Silent:1b,Health:1f}");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String hit = agent.mustCall("attack-entity", Map.of("bot", BotWorld.BOT, "name", "chicken"));

        /* A read the console answers either way: the chicken's health while it lives, and no entity once it is gone. */
        String left = untilTheServer("data get entity @e[type=chicken,tag=mcagents,limit=1] Health",
            answer -> answer.startsWith("No entity was found"));
        world.run("kill @e[type=chicken,tag=mcagents]");
        world.run("function mcagents:setup");

        assertEquals("Hit chicken 1 time(s).", hit);
        assertTrue(left.startsWith("No entity was found"), "the chicken outlived the hit: " + left);
    }

    /**
     * A right-click is walked into reach of the entity it names, and only the server says whether it
     * arrived: an interaction entity keeps the player that clicked it.
     */
    @Test
    void aRightClickIsRecordedByTheEntityItReached() {
        world.run("tp " + BotWorld.BOT + " 4 -60 -6");
        world.run("kill @e[type=interaction,tag=mcagents]");
        world.run("summon interaction 9 -60 -6 {Tags:[\"mcagents\"],width:1f,height:2f}");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String clicked = agent.mustCall("interact-entity", Map.of("bot", BotWorld.BOT, "name", "interaction"));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));

        String recorded = world.run("data get entity @e[type=interaction,tag=mcagents,limit=1] interaction");
        world.run("kill @e[type=interaction,tag=mcagents]");

        assertEquals("Right-clicked interaction.", clicked);
        assertTrue(recorded.contains("player"), recorded);
    }

    private static final String MANNEQUIN = "@e[type=mannequin,tag=mcagents_npc,limit=1]";
    private static final String HITBOX = "@e[type=interaction,tag=mcagents_npc,limit=1]";

    /**
     * The fixture's two NPCs, unclicked, with the bot a few blocks from both and a named name tag in
     * its hand. A mannequin keeps no record of a bare right-click, and the name a name tag leaves on
     * it is the record a click reached it.
     */
    private void standBesideTheNpcs() {
        world.run("function mcagents:npc");
        world.run("tp " + BotWorld.BOT + " 4.5 -60 -12.5");
        world.run("item replace entity " + BotWorld.BOT
            + " weapon.mainhand with minecraft:name_tag[custom_name={text:\"clicked\"}]");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
    }

    /** What the server holds on an entity, once it holds what a click leaves. */
    private String awaitRecord(String entity, String path, Predicate<String> recorded) {
        String read = "";

        for (int attempt = 0; attempt < 20; attempt++) {
            read = world.run("data get entity " + entity + " " + path);
            if (recorded.test(read)) {
                break;
            }
            agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));
        }
        return read;
    }

    /**
     * An NPC on a server with a resource pack has no name of its own: it is a mannequin, or a model
     * clicked through an interaction hitbox, and what a player reads is a text display floating over
     * it. Picked by that label, a case-insensitive part of it being enough.
     */
    @Test
    void anNpcIsRightClickedByTheLabelOverIt() {
        standBesideTheNpcs();

        String mannequin = agent.mustCall("interact-entity", Map.of("bot", BotWorld.BOT, "label", "Fisher Kim"));
        String hitbox = agent.mustCall("interact-entity", Map.of("bot", BotWorld.BOT, "label", "well keeper"));

        String named = awaitRecord(MANNEQUIN, "CustomName", read -> read.contains("clicked"));
        String clicked = awaitRecord(HITBOX, "interaction", read -> read.contains("player"));
        world.run("function mcagents:setup");

        assertEquals("Right-clicked mannequin.", mannequin);
        assertEquals("Right-clicked interaction.", hitbox);
        assertTrue(named.contains("clicked"), "the mannequin was never clicked: " + named);
        assertTrue(clicked.contains("player"), "the hitbox was never clicked: " + clicked);
    }

    @Test
    void anNpcIsHitByTheLabelOverIt() {
        standBesideTheNpcs();

        String mannequin = agent.mustCall("attack-entity", Map.of("bot", BotWorld.BOT, "label", "Fisher Kim"));
        String hitbox = agent.mustCall("attack-entity", Map.of("bot", BotWorld.BOT, "label", "Well Keeper"));

        String health = awaitRecord(MANNEQUIN, "Health", read -> !read.contains("20.0f"));
        String attacked = awaitRecord(HITBOX, "attack", read -> read.contains("player"));
        world.run("function mcagents:setup");

        assertEquals("Hit mannequin 1 time(s).", mannequin);
        assertEquals("Hit interaction 1 time(s).", hitbox);
        assertTrue(health.contains("entity data: ") && !health.contains("20.0f"), "the mannequin was never hurt: " + health);
        assertTrue(attacked.contains("player"), "the hitbox was never hit: " + attacked);
    }

    /**
     * find-entity names the label over each NPC and the id to click it by, and the id is what makes
     * the second call reach the entity the first one listed rather than whatever is nearest.
     */
    @Test
    void anNpcIsRightClickedByTheIdFindEntityGaveIt() {
        standBesideTheNpcs();

        String mannequins = agent.mustCall("find-entity",
            Map.of("bot", BotWorld.BOT, "type", "mannequin", "maxDistance", 16));
        String hitboxes = agent.mustCall("find-entity",
            Map.of("bot", BotWorld.BOT, "type", "interaction", "maxDistance", 16));

        Matcher mannequin = Pattern.compile("- mannequin labelled \"\\[nametag/label] Fisher Kim\" at \\(2, -60, -17\\), [\\d.]+ blocks away, id (\\d+)")
            .matcher(mannequins);
        Matcher hitbox = Pattern.compile("- interaction labelled \"Well Keeper\" at \\(6, -60, -17\\), [\\d.]+ blocks away, id (\\d+)")
            .matcher(hitboxes);
        assertTrue(mannequin.find(), mannequins);
        assertTrue(hitbox.find(), hitboxes);

        String clickedMannequin = agent.mustCall("interact-entity",
            Map.of("bot", BotWorld.BOT, "id", Integer.parseInt(mannequin.group(1))));
        String clickedHitbox = agent.mustCall("interact-entity",
            Map.of("bot", BotWorld.BOT, "id", Integer.parseInt(hitbox.group(1))));

        String named = awaitRecord(MANNEQUIN, "CustomName", read -> read.contains("clicked"));
        String clicked = awaitRecord(HITBOX, "interaction", read -> read.contains("player"));
        world.run("function mcagents:setup");

        assertEquals("Right-clicked mannequin.", clickedMannequin);
        assertEquals("Right-clicked interaction.", clickedHitbox);
        assertTrue(named.contains("clicked"), "the mannequin was never clicked: " + named);
        assertTrue(clicked.contains("player"), "the hitbox was never clicked: " + clicked);
    }

    /**
     * The crosshair is how a player clicks, and it is the one way to reach something a name or a
     * label cannot pick out. The tool does not turn the bot, so look-at does, and what it clicks is
     * whatever that left under the crosshair. Aimed at the NPC's legs: a block's middle at head height
     * put the ray a hand's width under the top of the mannequin.
     */
    @Test
    void anNpcIsRightClickedWhereTheCrosshairIs() {
        agent.requires("look-at");
        standBesideTheNpcs();

        world.run("tp " + BotWorld.BOT + " 2.5 -60 -14.0");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        agent.mustCall("look-at", Map.of("bot", BotWorld.BOT, "x", 2, "y", -60, "z", -17));
        String mannequin = agent.mustCall("interact-entity", Map.of("bot", BotWorld.BOT, "crosshair", true));

        world.run("tp " + BotWorld.BOT + " 6.5 -60 -14.0");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        agent.mustCall("look-at", Map.of("bot", BotWorld.BOT, "x", 6, "y", -60, "z", -17));
        String hitbox = agent.mustCall("interact-entity", Map.of("bot", BotWorld.BOT, "crosshair", true));

        agent.mustCall("look-at", Map.of("bot", BotWorld.BOT, "x", 6, "y", -40, "z", -14));
        String sky = agent.refusal("interact-entity", Map.of("bot", BotWorld.BOT, "crosshair", true));

        String named = awaitRecord(MANNEQUIN, "CustomName", read -> read.contains("clicked"));
        String clicked = awaitRecord(HITBOX, "interaction", read -> read.contains("player"));
        world.run("function mcagents:setup");

        assertEquals("Right-clicked mannequin.", mannequin);
        assertEquals("Right-clicked interaction.", hitbox);
        assertTrue(sky.contains("The crosshair is not on an entity within reach."), sky);
        assertTrue(named.contains("clicked"), "the mannequin was never clicked: " + named);
        assertTrue(clicked.contains("player"), "the hitbox was never clicked: " + clicked);
    }

    /**
     * Two ways of saying which entity can name two different ones, and picking either would click
     * something the caller may not have meant. Refused before anything is sent to the server.
     */
    @Test
    void twoWaysOfSayingWhichEntityAreRefused() {
        standBesideTheNpcs();
        world.run("tp " + BotWorld.BOT + " 6.5 -60 -14.0 180 14");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));

        String both = agent.refusal("interact-entity",
            Map.of("bot", BotWorld.BOT, "label", "Well Keeper", "crosshair", true));
        String none = agent.refusal("attack-entity", Map.of("bot", BotWorld.BOT, "crosshair", false));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String clicked = world.run("data get entity " + HITBOX + " interaction");
        String attacked = world.run("data get entity " + HITBOX + " attack");
        world.run("function mcagents:setup");

        assertTrue(both.contains("Say which entity with exactly one of name, label, id or crosshair; this call gave label and crosshair."), both);
        assertTrue(none.contains("Say which entity with exactly one of name, label, id or crosshair; this call gave none."), none);
        assertTrue(!clicked.contains("player"), "a refused call clicked the hitbox: " + clicked);
        assertTrue(!attacked.contains("player"), "a refused call hit the hitbox: " + attacked);
    }

    /**
     * A dead bot is still a player to the client, so every tool went on acting for it. A walk sent
     * the server nothing, waited out its whole deadline and reported a timeout, and nothing
     * anywhere said the bot was lying behind a death screen.
     */
    @Test
    void aDeadBotSaysSoInsteadOfTimingOut() {
        /* Nothing but respawn brings a bot back, and a bot left dead spoils every case after it. */
        agent.requires("respawn", "move-to-position", "list-inventory");
        world.run("kill " + BotWorld.BOT);
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 20));

        String refused = agent.refusal("move-to-position",
            Map.of("bot", BotWorld.BOT, "x", 4, "y", -60, "z", 0, "timeoutMs", 5000));
        String state = agent.call("get-player-state", Map.of("bot", BotWorld.BOT));
        String status = agent.call("get-bot-status", Map.of("bot", BotWorld.BOT));
        /*
        Reading goes on behind the death screen, because what a death did is read after it. The
        fixture carries three diamonds and keepInventory is off, so they went with the body.
        */
        String inventory = agent.mustCall("list-inventory", Map.of("bot", BotWorld.BOT));

        agent.mustCall("respawn", Map.of("bot", BotWorld.BOT));
        world.run("tp " + BotWorld.BOT + " 2 -59 0");
        world.run("function mcagents:setup");

        assertTrue(refused.contains("the bot is dead"), refused);
        assertTrue(state.startsWith("dead"), state);
        assertTrue(status.contains("Dead:"), status);
        assertTrue(!inventory.contains("diamond"), "the inventory still reads as it did before dying: " + inventory);
    }

    /**
     * Pressing respawn is one packet, and answering there said where the body fell: the server
     * sends a new player and then where it stands, and the position asked for straight after was
     * still the old one. Where the server put it is the only answer that counts.
     */
    @Test
    void aRespawnedBotIsWhereTheServerSentIt() {
        agent.requires("respawn", "get-position");
        world.run("spawnpoint " + BotWorld.BOT + " 0 -60 10");
        world.run("tp " + BotWorld.BOT + " 6 -60 -6");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        world.run("kill " + BotWorld.BOT);
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 20));

        String respawned = agent.mustCall("respawn", Map.of("bot", BotWorld.BOT));
        String position = agent.call("get-position", Map.of("bot", BotWorld.BOT));

        world.run("tp " + BotWorld.BOT + " 2 -59 0");
        world.run("function mcagents:setup");

        assertTrue(respawned.startsWith("Respawned at (0, -60, 10)"), respawned);
        assertTrue(position.contains("(0, -60, 10)"), position);
    }

    /**
     * A server marks a quest done by granting an advancement, and there was no way to see one: the
     * toast is gone in five seconds, and nothing read the progress the server had sent. The title
     * is drawn in the pack's own font and the id is on no screen, so both are asserted.
     */
    @Test
    void anAdvancementTheServerGrantsIsSeenAsItHappensAndAfter() {
        world.run("advancement revoke " + BotWorld.BOT + " only mcagents:quest/first_steps");
        world.run("advancement revoke " + BotWorld.BOT + " only mcagents:quest/gather");

        /* Granted after the wait starts, so the toast cannot be one left over from another run. */
        new Thread(() -> {
            sleep(1500);
            world.run("advancement grant " + BotWorld.BOT + " only mcagents:quest/first_steps");
        }).start();

        /* mustCall, because a wait that expires answers with the pattern in it and would pass. */
        String toast = agent.mustCall("wait-for-toast",
            Map.of("bot", BotWorld.BOT, "pattern", "mcagents:quest/first_steps", "timeoutMs", 20000));

        world.run("advancement grant " + BotWorld.BOT + " only mcagents:quest/gather wood");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String advancements = agent.call("read-advancements", Map.of("bot", BotWorld.BOT));

        world.run("advancement revoke " + BotWorld.BOT + " only mcagents:quest/first_steps");
        world.run("advancement revoke " + BotWorld.BOT + " only mcagents:quest/gather");

        assertTrue(toast.contains("[gui/label] First Steps (mcagents:quest/first_steps, goal)"), toast);
        assertTrue(advancements.contains("mcagents:quest/gather \"Gather Supplies\" (task): 1 of 2 criteria"),
            advancements);
        assertTrue(advancements.contains("mcagents:quest/first_steps \"[gui/label] First Steps\" (goal): done at"),
            advancements);
        assertTrue(advancements.indexOf("mcagents:quest/gather") < advancements.indexOf("mcagents:quest/first_steps"),
            "the most recently progressed did not come first: " + advancements);
        assertTrue(!advancements.contains("minecraft:"), "vanilla was listed without being asked for: " + advancements);
    }

    /**
     * A page turn is a button the server answers, so the page a lectern is open at is the server's.
     * Asserted on the lectern the server keeps as well as on the book read in front of it, and closed
     * the way any screen is, since a lectern is a book to the client and not a window.
     */
    @Test
    void aLecternIsReadAtThePageTheServerTurnedItTo() {
        world.run("function mcagents:setup");
        world.run("clear " + BotWorld.BOT);
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        agent.mustCall("activate-block", Map.of("bot", BotWorld.BOT, "x", 0, "y", -60, "z", 7));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String options = agent.mustCall("read-container-options", Map.of("bot", BotWorld.BOT));
        String pressed = agent.mustCall("press-container-button", Map.of("bot", BotWorld.BOT, "option", "page 2"));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        String book = agent.mustCall("read-book", Map.of("bot", BotWorld.BOT));
        String kept = world.run("data get block 0 -60 7 Page");
        String closed = agent.mustCall("close-window", Map.of("bot", BotWorld.BOT));

        world.run("function mcagents:setup");

        assertTrue(options.contains("(type minecraft:lectern) offers 3 options, open at page 1 of 3"), options);
        assertTrue(pressed.startsWith("Pressed \"page 2\" (button 101)"), pressed);
        assertTrue(book.contains("open at page 2"), book);
        assertTrue(kept.endsWith(": 1"), "the server's lectern is not at the second page: " + kept);
        assertTrue(closed.startsWith("Closed the lectern"), closed);
    }

    /**
     * A loom's patterns are pressed by a number that means whichever pattern the list puts there,
     * and the list depends on the dye. Asserted on the banner the server hands back, because the
     * client shows the pattern it asked for whether or not the server agreed.
     */
    @Test
    void aLoomPatternIsPressedByTheNameItIsShownAs() {
        world.run("clear " + BotWorld.BOT);
        world.run("give " + BotWorld.BOT + " white_banner 1");
        world.run("give " + BotWorld.BOT + " red_dye 1");
        agent.mustCall("wait-for-item", Map.of("bot", BotWorld.BOT, "pattern", "red_dye", "timeoutMs", 10000));
        agent.mustCall("activate-block", Map.of("bot", BotWorld.BOT, "x", -1, "y", -60, "z", 7));
        agent.mustCall("wait-for-window", Map.of("bot", BotWorld.BOT, "timeoutMs", 10000));
        /* The hotbar starts at 31 on a loom; a shift-click sends a banner and a dye to their own slots. */
        agent.mustCall("click-slot", Map.of("bot", BotWorld.BOT, "slot", 31, "shift", true));
        agent.mustCall("click-slot", Map.of("bot", BotWorld.BOT, "slot", 32, "shift", true));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String options = agent.mustCall("read-container-options", Map.of("bot", BotWorld.BOT));
        Matcher first = Pattern.compile("\n  \\d+\\. (.+?) \\[").matcher(options);
        assertTrue(first.find(), options);

        agent.mustCall("press-container-button", Map.of("bot", BotWorld.BOT, "option", first.group(1)));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        agent.mustCall("click-slot", Map.of("bot", BotWorld.BOT, "slot", 3, "shift", true));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        agent.call("close-window", Map.of("bot", BotWorld.BOT));

        String banner = world.run("data get entity " + BotWorld.BOT
            + " Inventory[{id:\"minecraft:white_banner\"}].components.\"minecraft:banner_patterns\"");
        world.run("function mcagents:setup");

        assertTrue(options.contains("Red Base [stripe_bottom]"), options);
        assertTrue(banner.contains("minecraft:red") || banner.contains("color: \"red\""),
            "the server's banner has no red pattern on it: " + banner);
    }

    /**
     * A beacon's effect buttons are icons with nothing to press by name, and the confirm button sends
     * both effects at once. What the pyramid is too low for is refused before it is sent -- 26.2
     * drops a client that sends it, and 26.1.2 quietly applies it -- and what was set is asked of the
     * beacon, since the window closes on confirm and the bot is left with nothing to read.
     */
    @Test
    void aBeaconIsSetToAnEffectItsPyramidAllows() {
        world.run("function mcagents:setup");
        world.run("clear " + BotWorld.BOT);
        world.run("give " + BotWorld.BOT + " iron_ingot 1");
        world.run("tp " + BotWorld.BOT + " 6 -60 8");
        agent.mustCall("wait-for-item", Map.of("bot", BotWorld.BOT, "pattern", "iron_ingot", "timeoutMs", 10000));
        /* open-container is for blocks that hold items; a beacon's menu opens like any other block's. */
        agent.mustCall("activate-block", Map.of("bot", BotWorld.BOT, "x", 6, "y", -60, "z", 10));
        agent.mustCall("wait-for-window", Map.of("bot", BotWorld.BOT, "timeoutMs", 10000));

        String options = awaitPyramid();
        String unpaid = agent.refusal("set-beacon-effects", Map.of("bot", BotWorld.BOT, "primary", "speed"));
        /* The first hotbar slot is 28 on a beacon, and a shift-click sends a single ingot to the payment slot. */
        agent.mustCall("click-slot", Map.of("bot", BotWorld.BOT, "slot", 28, "shift", true));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        String paid = agent.mustCall("read-container-options", Map.of("bot", BotWorld.BOT));

        String tooHigh = agent.refusal("set-beacon-effects", Map.of("bot", BotWorld.BOT, "primary", "Strength"));
        String noSecondary = agent.refusal("set-beacon-effects",
            Map.of("bot", BotWorld.BOT, "primary", "speed", "secondary", "regeneration"));
        String set = agent.mustCall("set-beacon-effects", Map.of("bot", BotWorld.BOT, "primary", "speed"));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        /* The server sets an effect only in the same step that takes the payment, so this says both. */
        String primary = world.run("data get block 6 -60 10 primary_effect");
        agent.call("close-window", Map.of("bot", BotWorld.BOT));
        world.run("function mcagents:setup");

        assertTrue(unpaid.contains("payment slot (slot 0) is empty"), unpaid);
        assertTrue(options.contains("Strength [strength], primary, 3 levels, unavailable"), options);
        assertTrue(paid.contains("with iron_ingot in the payment slot"), paid);
        assertTrue(tooHigh.contains("Strength [strength] needs a pyramid of 3 levels, and this beacon's has 1 level"),
            tooHigh);
        assertTrue(noSecondary.contains("a secondary effect needs a pyramid of 4 levels"), noSecondary);
        assertTrue(set.startsWith("Set the beacon's primary effect to Speed [speed] with no secondary effect, "
            + "paying one iron_ingot"), set);
        assertTrue(primary.contains("\"minecraft:speed\""), primary);
    }

    /** A beacon counts its pyramid every four seconds, and a fresh one has counted nothing yet. */
    private String awaitPyramid() {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        String options = agent.mustCall("read-container-options", Map.of("bot", BotWorld.BOT));

        while (!options.contains("a pyramid of 1 level") && Instant.now().isBefore(deadline)) {
            sleep(500);
            options = agent.mustCall("read-container-options", Map.of("bot", BotWorld.BOT));
        }
        return options;
    }

    /**
     * A bundle gives up its first item to a right click unless another was chosen by scrolling, and
     * the choice is sent on its own, before the click. Choosing the second and taking one out is
     * asserted on the bundle the server keeps in the chest, since the client shows its own guess.
     */
    @Test
    void aBundleGivesUpTheItemChosenInIt() {
        world.run("function mcagents:setup");
        world.run("item replace block 1 -60 3 container.13 with minecraft:bundle[minecraft:bundle_contents="
            + "[{id:\"minecraft:arrow\",count:5},{id:\"minecraft:string\",count:2},{id:\"minecraft:feather\",count:3}]]");
        world.run("tp " + BotWorld.BOT + " 2 -60 2");
        agent.mustCall("open-container", Map.of("bot", BotWorld.BOT, "x", 1, "y", -60, "z", 3));

        String missing = agent.refusal("select-bundle-item", Map.of("bot", BotWorld.BOT, "slot", 13, "item", "diamond"));
        String notABundle = agent.refusal("select-bundle-item", Map.of("bot", BotWorld.BOT, "slot", 4, "item", "1"));
        String selected = agent.mustCall("select-bundle-item",
            Map.of("bot", BotWorld.BOT, "slot", 13, "item", "string"));
        agent.mustCall("click-slot", Map.of("bot", BotWorld.BOT, "slot", 13, "button", "right"));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        agent.mustCall("close-window", Map.of("bot", BotWorld.BOT));

        String bundle = world.run("data get block 1 -60 3 Items[{Slot:13b}].components.\"minecraft:bundle_contents\"");
        world.run("function mcagents:setup");

        assertTrue(missing.contains("It holds 1. arrow x5, 2. string x2, 3. feather x3."), missing);
        assertTrue(notABundle.contains("slot 4 holds cooked_beef"), notABundle);
        assertTrue(selected.startsWith("Selected 2. string x2 in the bundle in slot 13."), selected);
        assertTrue(bundle.contains("minecraft:arrow") && bundle.contains("minecraft:feather"), bundle);
        assertTrue(!bundle.contains("minecraft:string"), "the server took something other than the string: " + bundle);
    }

    /**
     * A middle-click is only a position; the server decides what comes of it. Creative makes the item,
     * and with Ctrl the block's contents too. Survival only moves one that is already carried onto the
     * hotbar. The hand is asked of the server, because the client's hand is set by the server's answer
     * and a read taken before it arrives shows the hand from before.
     */
    @Test
    void aPickedBlockEndsUpInTheHandTheServerSees() {
        world.run("function mcagents:setup");
        agent.call("close-window", Map.of("bot", BotWorld.BOT));
        world.run("clear " + BotWorld.BOT);
        world.run("tp " + BotWorld.BOT + " 6 -60 -1");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String creative = agent.mustCall("pick-block", Map.of("bot", BotWorld.BOT, "x", 7, "y", -60, "z", -1));
        String made = world.run("execute if items entity " + BotWorld.BOT + " weapon.mainhand minecraft:diamond_block");

        world.run("gamemode survival " + BotWorld.BOT);
        world.run("clear " + BotWorld.BOT);
        world.run("item replace entity " + BotWorld.BOT + " inventory.0 with minecraft:diamond_block 3");
        agent.mustCall("wait-for-item", Map.of("bot", BotWorld.BOT, "pattern", "diamond_block", "timeoutMs", 10000));
        String survival = agent.mustCall("pick-block", Map.of("bot", BotWorld.BOT, "x", 7, "y", -60, "z", -1));
        String moved = world.run("execute if items entity " + BotWorld.BOT + " weapon.mainhand minecraft:diamond_block");
        String left = world.run("execute if items entity " + BotWorld.BOT + " inventory.0 minecraft:diamond_block");
        String notCarried = agent.refusal("pick-block", Map.of("bot", BotWorld.BOT, "x", 3, "y", -60, "z", 0));
        world.run("gamemode creative " + BotWorld.BOT);

        agent.mustCall("pick-block", Map.of("bot", BotWorld.BOT, "x", 1, "y", -60, "z", 3, "includeData", true));
        String withData = world.run("execute if items entity " + BotWorld.BOT
            + " weapon.mainhand minecraft:chest[minecraft:container]");
        world.run("function mcagents:setup");

        assertTrue(creative.startsWith("Picked diamond_block from (7, -60, -1); the bot now holds diamond_block x1"),
            creative);
        assertTrue(made.startsWith("Test passed"), made);
        assertTrue(survival.contains("the bot now holds diamond_block x3"), survival);
        assertTrue(moved.startsWith("Test passed"), moved);
        assertTrue(left.startsWith("Test failed"), left);
        assertTrue(notCarried.contains("the bot has no oak_sign"), notCarried);
        assertTrue(withData.startsWith("Test passed"), withData);
    }

    /**
     * The creative inventory's slots are an item picker, and on a tab of items a click takes a stack
     * of one. Sending the click as a container packet instead clicked the picker's slot number on the
     * real inventory -- the crafting result, for slot 0 -- and nothing arrived anywhere.
     */
    @Test
    void theCreativeInventoryIsClickedTheWayACreativePlayerClicksIt() {
        world.run("function mcagents:setup");
        agent.call("close-window", Map.of("bot", BotWorld.BOT));

        String opened = agent.mustCall("open-inventory", Map.of("bot", BotWorld.BOT, "tab", "Building Blocks"));
        String window = agent.mustCall("read-window", Map.of("bot", BotWorld.BOT));
        Matcher offered = Pattern.compile("\n  0: (\\S+) x").matcher(window);
        assertTrue(offered.find(), window);

        /* The last nine slots are the hotbar, and the ninth hotbar slot is empty in the fixture. */
        int hotbarNine = Integer.parseInt(Pattern.compile("(\\d+) slots").matcher(window).results()
            .findFirst().orElseThrow().group(1)) - 1;
        agent.mustCall("click-slot", Map.of("bot", BotWorld.BOT, "slot", 0));
        agent.mustCall("click-slot", Map.of("bot", BotWorld.BOT, "slot", hotbarNine));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        agent.call("close-window", Map.of("bot", BotWorld.BOT));

        String hotbar = world.run("data get entity " + BotWorld.BOT + " Inventory[{Slot:8b}].id");
        world.run("function mcagents:setup");

        assertTrue(opened.startsWith("Opened the creative inventory on its \"Building Blocks\" tab"), opened);
        assertTrue(hotbar.contains("minecraft:" + offered.group(1)),
            "the ninth hotbar slot does not hold what slot 0 offered (" + offered.group(1) + "): " + hotbar);
    }

    /**
     * The client holds the statistics it was last sent, and it is sent them only when it asks, so
     * reading what it holds answers a quest check with the count from before the kill being checked.
     * Read, kill, read, kill, read: each read has to be the number the server has after the kill,
     * and the server's number is a scoreboard objective counting the same statistic.
     */
    @Test
    void aKillIsReadAsTheServerCountsItAfterwards() {
        world.run("function mcagents:setup");
        world.run("tp " + BotWorld.BOT + " 4 -60 -8");
        world.run("scoreboard objectives remove mcagents_kills");
        world.run("scoreboard objectives add mcagents_kills minecraft.killed:minecraft.chicken");
        Map<String, Object> chickens = Map.of("bot", BotWorld.BOT, "type", "killed", "target", "minecraft:chicken");

        String before = agent.mustCall("read-stats", chickens);
        int counted = Integer.parseInt(before.substring(before.lastIndexOf(' ') + 1));

        killAChicken(counted + 1);
        String once = agent.mustCall("read-stats", chickens);
        killAChicken(counted + 2);
        String twice = agent.mustCall("read-stats", chickens);
        String general = agent.mustCall("read-stats", Map.of("bot", BotWorld.BOT));

        world.run("scoreboard objectives remove mcagents_kills");
        world.run("kill @e[type=chicken,tag=mcagents]");
        world.run("tp " + BotWorld.BOT + " 2 -59 0");
        world.run("function mcagents:setup");

        assertTrue(before.startsWith("Statistic minecraft:killed minecraft:chicken: "), before);
        assertEquals("Statistic minecraft:killed minecraft:chicken: " + (counted + 1), once);
        assertEquals("Statistic minecraft:killed minecraft:chicken: " + (counted + 2), twice);
        assertTrue(general.contains("\n  minecraft:mob_kills: "), general);
        assertTrue(general.contains("minecraft:killed ("), "the kills per mob went unmentioned: " + general);
    }

    /** One chicken, dead by the bot's hand, and not returning until the server has counted it. */
    private void killAChicken(int expected) {
        world.run("summon chicken 6 -60 -8 {Tags:[\"mcagents\"],NoAI:1b,Silent:1b,Health:1f}");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        agent.mustCall("attack-entity", Map.of("bot", BotWorld.BOT, "name", "chicken"));

        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        String score = "";

        while (Instant.now().isBefore(deadline)) {
            score = world.run("scoreboard players get " + BotWorld.BOT + " mcagents_kills");
            if (score.contains("has " + expected + " ")) {
                return;
            }
            sleep(250);
        }
        throw new AssertionError("the server never counted kill " + expected + ": " + score);
    }

    /**
     * A command block's editor is a text field, a mode that cycles and a Done button, the same
     * pieces a dialog is made of. It is read back from the block, because the editor shows what was
     * typed whether or not the server took it.
     */
    @Test
    void aCommandBlockIsSetFromItsEditor() {
        world.run("function mcagents:setup");
        world.run("tp " + BotWorld.BOT + " 4 -60 -8");
        world.run("setblock 4 -60 -10 command_block{Command:\"say before\"}");
        agent.call("close-window", Map.of("bot", BotWorld.BOT));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        agent.mustCall("activate-block", Map.of("bot", BotWorld.BOT, "x", 4, "y", -60, "z", -10));
        /*
        The editor opens before the server sends the block's command, and the command overwrites the
        field when it lands. type-text refuses until then; this waits so the case is about the editor.
        */
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        String typed = agent.mustCall("type-text",
            Map.of("bot", BotWorld.BOT, "field", "Console Command", "text", "say from the editor"));
        String mode = agent.mustCall("press-dialog-button", Map.of("bot", BotWorld.BOT, "label", "Impulse"));
        agent.mustCall("press-dialog-button", Map.of("bot", BotWorld.BOT, "label", "Done"));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String command = world.run("data get block 4 -60 -10 Command");
        String chain = world.run("execute if block 4 -60 -10 minecraft:chain_command_block");
        String after = agent.mustCall("close-window", Map.of("bot", BotWorld.BOT));

        world.run("setblock 4 -60 -10 air");
        world.run("tp " + BotWorld.BOT + " 2 -59 0");
        world.run("function mcagents:setup");

        assertTrue(typed.contains("typed into \"Console Command\""), typed);
        assertTrue(mode.contains("pressed \"Impulse\""), mode);
        assertTrue(command.contains("\"say from the editor\""), "the block holds something else: " + command);
        assertTrue(chain.contains("passed"), "the mode did not reach the block: " + chain);
        assertTrue(after.startsWith("No window was open"), "Done left the editor open: " + after);
    }

    /**
     * The end credits are the one screen a player leaves by respawning. Closing it the way Escape
     * does is what sends the respawn, and the server is the one that says where the bot went.
     */
    @Test
    void theEndCreditsCloseIntoTheOverworld() {
        /* The credits hold a bot outside every world until it asks to leave them, which is close-window. */
        agent.requires("close-window", "get-bot-status");
        world.run("execute in minecraft:the_end run forceload add 0 0");
        world.run("execute in minecraft:the_end run setblock 0 60 0 minecraft:end_portal");
        world.run("execute in minecraft:the_end run tp " + BotWorld.BOT + " 0.5 60 0.5");

        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        String seen = "";
        while (Instant.now().isBefore(deadline) && !seen.contains("1b")) {
            seen = world.run("data get entity " + BotWorld.BOT + " seenCredits");
            sleep(250);
        }
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 20));

        String closed = agent.mustCall("close-window", Map.of("bot", BotWorld.BOT));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 40));
        String dimension = world.run("data get entity " + BotWorld.BOT + " Dimension");

        world.run("execute in minecraft:the_end run setblock 0 60 0 minecraft:air");
        world.run("execute in minecraft:the_end run forceload remove 0 0");
        world.run("tp " + BotWorld.BOT + " 2 -59 0");
        world.run("function mcagents:setup");

        assertTrue(seen.contains("1b"), "the server never showed the credits: " + seen);
        assertEquals("Closed the end credits.", closed);
        assertTrue(dimension.contains("minecraft:overworld"), "the bot is still in the End: " + dimension);
    }

    /**
     * A restart is a leave and a join under the same name, and the server's count of leaves is what
     * says the bot really went, and its position that it came back.
     *
     * <p>Twice in a row, because the second restart leaves a connection that has only just logged in.
     * An azalea bot sent that join while the connection was still closing, was answered Ok for a join
     * it never made, and waited out the spawn timeout in no world. A server with a connection throttle
     * refuses such a quick rejoin outright, which is why this one runs without one.
     */
    @Test
    void aRestartedBotReallyLeftAndIsBackInTheWorld() {
        world.run("scoreboard objectives remove mcagents_leaves");
        world.run("scoreboard objectives add mcagents_leaves minecraft.custom:minecraft.leave_game");

        agent.mustCall("restart-bot", Map.of("bot", BotWorld.BOT));
        String restarted = agent.mustCall("restart-bot", Map.of("bot", BotWorld.BOT));
        /* On the ground, so where it stands does not depend on whether a kind of bot falls. */
        world.run("tp " + BotWorld.BOT + " 2 -60 0");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        String position = agent.mustCall("get-position", Map.of("bot", BotWorld.BOT));
        String leaves = world.run("scoreboard players get " + BotWorld.BOT + " mcagents_leaves");
        world.run("scoreboard objectives remove mcagents_leaves");

        assertTrue(restarted.startsWith("Restarted."), restarted);
        assertTrue(leaves.contains("has 2 "), "the server did not see the bot leave twice: " + leaves);
        assertEquals("Position: (2, -60, 0)", position);
    }

    /**
     * The block a bot stands in, on the negative side of an axis. Truncating towards zero names the
     * block next door there and only there, which is how one kind of bot reported a position a block
     * away from where the server had put it.
     */
    @Test
    void aBotIsInTheBlockTheServerPutItIn() {
        /* Dry ground: the fixture's fishing pool is further along the negative axes, and a bot in water sinks. */
        world.run("tp " + BotWorld.BOT + " -1.5 -60 1.5");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String position = agent.mustCall("get-position", Map.of("bot", BotWorld.BOT));

        assertEquals("Position: (-2, -60, 1)", position);
    }

    /**
     * What a bot says reaches the server as that player's chat, and comes back on the feed attributed
     * to it. Both halves are the bot's: sending it, and naming who a line came from rather than
     * where the client drew it.
     */
    @Test
    void whatABotSaysIsHeardAsThatPlayer() {
        String said = agent.mustCall("send-chat", Map.of("bot", BotWorld.BOT, "message", "probe says hello"));
        String heard = agent.mustCall("wait-for-chat",
            Map.of("bot", BotWorld.BOT, "pattern", "probe says hello", "timeoutMs", 10000));

        assertEquals("Sent as " + BotWorld.BOT + ": probe says hello", said);
        assertTrue(heard.contains(BotWorld.BOT), heard);
    }

    /**
     * A turn of the head is only the client's until it is sent. The server's own record of the
     * rotation is asserted, because a bot that turns locally and says "Looking at" has told an
     * agent something no plugin watching the player will ever see.
     */
    @Test
    void theServerSeesTheBotLookWhereItWasTold() {
        world.run("tp " + BotWorld.BOT + " 11 -60 0");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));

        String east = agent.mustCall("look-at", Map.of("bot", BotWorld.BOT, "x", 14, "y", -59, "z", 0));
        String facingEast = untilTheServer("data get entity " + BotWorld.BOT + " Rotation",
            rotation -> facing(rotation, -90, 2));
        agent.mustCall("look-at", Map.of("bot", BotWorld.BOT, "x", 11, "y", -61, "z", -3));
        String facingDown = untilTheServer("data get entity " + BotWorld.BOT + " Rotation",
            rotation -> facing(rotation, 180, 35));

        assertEquals("Looking at (14, -59, 0).", east);
        assertTrue(facing(facingEast, -90, 2), facingEast);
        assertTrue(facing(facingDown, 180, 35), facingDown);
    }

    /** A jump the server believed is one it counted, in the statistic a parkour plugin would read. */
    @Test
    void aJumpIsOneTheServerCounts() {
        world.run("tp " + BotWorld.BOT + " 2 -60 0");
        world.run("scoreboard objectives remove mcagents_jumps");
        world.run("scoreboard objectives add mcagents_jumps minecraft.custom:minecraft.jump");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String jumped = agent.mustCall("jump", Map.of("bot", BotWorld.BOT));
        String counted = untilTheServer("scoreboard players get " + BotWorld.BOT + " mcagents_jumps",
            score -> score.contains("has 1 "));
        world.run("scoreboard objectives remove mcagents_jumps");

        assertEquals("Jumped.", jumped);
        assertTrue(counted.contains("has 1 "), counted);
    }

    /**
     * A sneaking player does not set off a pressure plate, and plugins read the crouch to mean
     * something, so the crouch has to be the server's and not only the client's. It is let go of
     * again before the case ends: a bot left crouching walks every case after it at a third of
     * the speed.
     */
    @Test
    void aCrouchIsOneTheServerSees() {
        world.run("tp " + BotWorld.BOT + " 9 -60 8");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        /* "unless" rather than a failed "if", because a failed test answers the console with nothing. */
        String predicate = " predicate {condition:\"minecraft:entity_properties\",entity:\"this\","
            + "predicate:{flags:{is_sneaking:true}}}";

        String crouched = agent.mustCall("set-stance", Map.of("bot", BotWorld.BOT, "sneak", true));
        String whileCrouched = untilTheServer("execute as " + BotWorld.BOT + " if" + predicate,
            answer -> answer.startsWith("Test passed"));
        String stood = agent.mustCall("set-stance", Map.of("bot", BotWorld.BOT, "sneak", false));
        String afterStanding = untilTheServer("execute as " + BotWorld.BOT + " unless" + predicate,
            answer -> answer.startsWith("Test passed"));

        assertTrue(crouched.startsWith("sneaking: true, "), "crouching: " + crouched);
        assertTrue(whileCrouched.startsWith("Test passed"), "the server saw no crouch: " + whileCrouched);
        assertTrue(stood.startsWith("sneaking: false, "), "standing: " + stood);
        assertTrue(afterStanding.startsWith("Test passed"), "the server saw no standing up: " + afterStanding);
    }

    /**
     * Holding a key moves the bot the way it faces and lets go afterwards. Where it ended is read
     * from the server twice, a second apart, because a key left down still reads as having moved.
     */
    @Test
    void aHeldKeyWalksTheBotTheWayItFacesAndStops() {
        /* Facing west, where nothing stands in the way for the two blocks a half-second walk covers. */
        world.run("tp " + BotWorld.BOT + " 9 -60 8 90 0");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String moved = agent.mustCall("move-in-direction",
            Map.of("bot", BotWorld.BOT, "direction", "forward", "durationMs", 500));
        String stopped = untilTheServer("data get entity " + BotWorld.BOT + " Pos", pos -> numbers(pos)[0] < 8.5);
        sleep(1000);
        String later = world.run("data get entity " + BotWorld.BOT + " Pos");

        assertEquals("Moved forward for 500ms.", moved);
        assertTrue(numbers(stopped)[0] < 8.5, "the bot did not walk west: " + stopped);
        assertTrue(Math.abs(numbers(stopped)[2] - 8.5) < 0.3, "the bot did not walk straight: " + stopped);
        assertTrue(Math.abs(numbers(later)[0] - numbers(stopped)[0]) < 0.3, "the key stayed down: " + later);
    }

    /** The fixture's wall is three blocks high, so arriving on its far side means going round it. */
    @Test
    void aWalkGoesRoundTheWallInTheWay() {
        world.run("tp " + BotWorld.BOT + " 11 -60 0");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String walked = agent.mustCall("move-to-position",
            Map.of("bot", BotWorld.BOT, "x", 17, "y", -60, "z", 0, "timeoutMs", 50000));
        String pos = untilTheServer("data get entity " + BotWorld.BOT + " Pos", answer -> numbers(answer)[0] > 15);

        assertEquals("Moved to within 1 block(s) of (17, -60, 0).", walked);
        assertTrue(numbers(pos)[0] > 15 && Math.abs(numbers(pos)[2]) < 2, "the server has the bot elsewhere: " + pos);
    }

    /**
     * A target nobody can walk to is refused with how far the bot got, and the bot stays there. A
     * walk left running after its refusal carried the bot on behind the caller's back.
     */
    @Test
    void aWalkThatCannotArriveSaysSoAndStops() {
        world.run("fill 19 -61 9 21 -58 11 minecraft:stone hollow");
        world.run("tp " + BotWorld.BOT + " 11 -60 10");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String refused = agent.refusal("move-to-position",
            Map.of("bot", BotWorld.BOT, "x", 20, "y", -60, "z", 10, "timeoutMs", 20000));
        String refusedAt = world.run("data get entity " + BotWorld.BOT + " Pos");
        sleep(1500);
        String later = world.run("data get entity " + BotWorld.BOT + " Pos");
        world.run("fill 19 -61 9 21 -58 11 minecraft:air");
        world.run("fill 19 -61 9 21 -61 11 minecraft:grass_block");

        assertTrue(refused.contains("could not reach (20, -60, 10) within 20000ms; it stopped "), refused);
        assertTrue(distance(numbers(refusedAt), numbers(later)) < 0.3, "the bot walked on: " + refusedAt + " then " + later);
    }

    /**
     * Survival, where breaking takes as long as the hand takes and the client has to keep hitting
     * until the server agrees. The block is out of reach, so the bot walks to it first.
     */
    @Test
    void aBlockIsBrokenWhereTheServerSeesItGo() {
        world.run("gamemode survival " + BotWorld.BOT);
        world.run("clear " + BotWorld.BOT);
        world.run("setblock 10 -60 -4 minecraft:dirt");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));

        String dug = agent.mustCall("dig-block", Map.of("bot", BotWorld.BOT, "x", 10, "y", -60, "z", -4));
        String gone = untilTheServer("execute if block 10 -60 -4 minecraft:air", answer -> answer.startsWith("Test passed"));
        String again = agent.mustCall("dig-block", Map.of("bot", BotWorld.BOT, "x", 10, "y", -60, "z", -4));
        world.run("kill @e[type=item]");
        world.run("function mcagents:setup");

        assertEquals("Dug dirt at (10, -60, -4).", dug);
        assertTrue(gone.startsWith("Test passed"), gone);
        assertEquals("Nothing to dig at (10, -60, -4).", again);
    }

    /**
     * A block goes against the face it was asked to go against. A log shows which: it lies along
     * the axis of the face it was put on. A right-click whose hit was made up -- the middle of the
     * block, on top -- stands the log upright on the neighbour instead of beside it.
     */
    @Test
    void aBlockIsPlacedAgainstTheFaceItWasAskedFor() {
        world.run("setblock 10 -60 -5 minecraft:stone");
        world.run("setblock 10 -60 -4 minecraft:air");
        world.run("item replace entity " + BotWorld.BOT + " weapon.mainhand with minecraft:oak_log");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));

        String placed = agent.mustCall("place-block",
            Map.of("bot", BotWorld.BOT, "x", 10, "y", -60, "z", -4, "faceDirection", "north"));
        String log = untilTheServer("execute if block 10 -60 -4 minecraft:oak_log[axis=z]",
            answer -> answer.startsWith("Test passed"));
        String again = agent.mustCall("place-block",
            Map.of("bot", BotWorld.BOT, "x", 10, "y", -60, "z", -4, "faceDirection", "north"));
        world.run("fill 10 -60 -5 10 -60 -4 minecraft:air");
        world.run("function mcagents:setup");

        assertEquals("Placed a block at (10, -60, -4) against its north face.", placed);
        assertTrue(log.startsWith("Test passed"), log);
        assertEquals("(10, -60, -4) already holds oak_log.", again);
    }

    /** A right-click the server acted on, which for a lever is the power it now gives. */
    @Test
    void aLeverIsPulledByRightClickingIt() {
        world.run("setblock 10 -60 -8 minecraft:lever[face=floor,facing=north,powered=false]");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));

        String clicked = agent.mustCall("activate-block", Map.of("bot", BotWorld.BOT, "x", 10, "y", -60, "z", -8));
        String powered = untilTheServer("execute if block 10 -60 -8 minecraft:lever[powered=true]",
            answer -> answer.startsWith("Test passed"));
        world.run("setblock 10 -60 -8 minecraft:air");

        assertEquals("Right-clicked lever at (10, -60, -8).", clicked);
        assertTrue(powered.startsWith("Test passed"), powered);
    }

    /**
     * A bow fires on release, with the power of however long it was drawn, so an arrow in the world
     * is a draw that lasted and a release that reached the server. A use let go of at once fires
     * nothing.
     */
    @Test
    void aBowDrawnForAWhileLoosesAnArrow() {
        world.run("kill @e[type=minecraft:arrow]");
        world.run("tp " + BotWorld.BOT + " 9 -60 8 0 -20");
        world.run("item replace entity " + BotWorld.BOT + " weapon.mainhand with minecraft:bow");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String used = agent.mustCall("use-held-item", Map.of("bot", BotWorld.BOT, "holdMs", 1000));
        String arrow = untilTheServer("execute if entity @e[type=minecraft:arrow]", answer -> answer.startsWith("Test passed"));
        world.run("kill @e[type=minecraft:arrow]");
        world.run("function mcagents:setup");

        assertEquals("Used bow x1 in the main hand, held for 1000ms and released.", used);
        assertTrue(arrow.startsWith("Test passed"), arrow);
    }

    /**
     * A plain use is left in use, which is how food is eaten: the client that let go of the button
     * a tick later put the food down after one bite. The server's count of what was eaten is the
     * proof, since it only counts an item used up.
     */
    @Test
    void foodUsedOnceIsEatenToTheEnd() {
        world.run("tp " + BotWorld.BOT + " 9 -60 8");
        world.run("gamemode survival " + BotWorld.BOT);
        /* Hungry first: a full player cannot eat, and on peaceful a player never goes hungry. */
        world.run("difficulty easy");
        world.run("effect give " + BotWorld.BOT + " minecraft:hunger 5 255 true");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 100));
        world.run("effect clear " + BotWorld.BOT);
        world.run("scoreboard objectives remove mcagents_eaten");
        world.run("scoreboard objectives add mcagents_eaten minecraft.used:minecraft.cooked_beef");
        world.run("item replace entity " + BotWorld.BOT + " weapon.mainhand with minecraft:cooked_beef 4");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));

        String used = agent.mustCall("use-held-item", Map.of("bot", BotWorld.BOT));
        String eaten = untilTheServer("scoreboard players get " + BotWorld.BOT + " mcagents_eaten",
            score -> score.contains("has 1 "));
        world.run("scoreboard objectives remove mcagents_eaten");
        world.run("difficulty peaceful");
        world.run("function mcagents:setup");

        assertEquals("Used cooked_beef x4 in the main hand.", used);
        assertTrue(eaten.contains("has 1 "), "the server counted nothing eaten: " + eaten);
    }

    /** What a console command answers, asked again until it says what was expected or five seconds pass. */
    private String untilTheServer(String command, java.util.function.Predicate<String> expected) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        String answer = world.run(command);

        while (!expected.test(answer) && Instant.now().isBefore(deadline)) {
            sleep(100);
            answer = world.run(command);
        }
        return answer;
    }

    /** The numbers in a data answer, in order: "[1.5d, -60.0d, 0.5d]" is 1.5, -60 and 0.5. */
    private static double[] numbers(String answer) {
        Matcher number = Pattern.compile("-?\\d+(\\.\\d+)?(E-?\\d+)?(?=[dfDF])").matcher(answer.substring(answer.indexOf('[') + 1));
        return number.results().mapToDouble(found -> Double.parseDouble(found.group())).toArray();
    }

    /** Whether a rotation answer's yaw is the one expected, and its pitch too, a degree either way. */
    private static boolean facing(String rotation, double yaw, double pitch) {
        double[] angles = numbers(rotation);
        if (angles.length < 2) {
            return false;
        }
        double turned = ((angles[0] - yaw) % 360 + 540) % 360 - 180;
        return Math.abs(turned) < 1 && Math.abs(angles[1] - pitch) < 1;
    }

    private static double distance(double[] a, double[] b) {
        return Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
    }

    /** The reason this kind of bot exists: a frame of what is actually on the screen. */
    @Test
    void aScreenshotComesBackAsAnImage() {
        int blobs = agent.blobs("screenshot", Map.of("bot", BotWorld.BOT, "width", 854, "height", 480));

        assertEquals(1, blobs, "screenshot returned no image");
    }
    /**
     * A quest shows its progress on the action bar, and a HUD is written in pieces: an icon glyph in
     * the pack's own font with the labels after it. Sent after the waits start, so what they match
     * is what the server drew and not a line some earlier case left behind.
     */
    @Test
    void whatTheServerDrawsOnTheHudArrivesOnItsFeeds() {
        new Thread(() -> {
            sleep(1500);
            world.run("function mcagents:hud");
        }).start();

        String actionBar = agent.mustCall("wait-for-action-bar",
            Map.of("bot", BotWorld.BOT, "pattern", "Mana", "timeoutMs", 20000));
        agent.mustCall("wait-for-title", Map.of("bot", BotWorld.BOT, "pattern", "survive", "timeoutMs", 10000));
        String titles = agent.call("read-title", Map.of("bot", BotWorld.BOT, "count", 2));

        assertTrue(actionBar.contains("[illageralt] Mana  | [illageralt] 40 | [illageralt] /40"), actionBar);
        assertTrue(titles.contains("title: Wave 3"), titles);
        assertTrue(titles.contains("subtitle: survive 60s"), titles);
    }

    /** A boss bar counting a quest up, read as it changes. */
    @Test
    void aBossBarIsReadAsItFills() {
        /* The fixture adds everyone online when it loads, which is before the bot is. */
        world.run("bossbar set minecraft:mcagents players " + BotWorld.BOT);

        new Thread(() -> {
            sleep(1500);
            world.run("bossbar set minecraft:mcagents value 90");
        }).start();

        String bar = agent.mustCall("wait-for-boss-bars",
            Map.of("bot", BotWorld.BOT, "pattern", "90%", "timeoutMs", 20000));
        world.run("bossbar set minecraft:mcagents value 73");

        assertTrue(bar.contains("boss bar \"Probe Bar\" (90%, purple, 10 notches)"), bar);
    }

    /** A command's answer is chat, and it comes back with the call that sent the command. */
    @Test
    void aCommandComesBackWithWhatTheServerSaid() {
        String ran = agent.mustCall("run-command", Map.of("bot", BotWorld.BOT, "command", "difficulty"));

        assertTrue(ran.startsWith("Ran /difficulty."), ran);
        assertTrue(ran.contains("The difficulty is Peaceful"), ran);
    }

    /** What completes a command is the server's to say, and it knows every command it has. */
    @Test
    void aPartialCommandIsCompletedByTheServer() {
        String completed = agent.mustCall("complete-command", Map.of("bot", BotWorld.BOT, "text", "/weat"));

        assertTrue(completed.startsWith("1 completions for \"/weat\""), completed);
        assertTrue(completed.contains("weather"), completed);
    }

    @Test
    void theTabListHasTheBotInIt() {
        String players = agent.mustCall("read-player-list", Map.of("bot", BotWorld.BOT));

        assertTrue(players.contains(BotWorld.BOT + " (this bot): creative, "), players);
    }

    /** The clock is the world's, and day and night are where the sky says they are. */
    @Test
    void theTimeOfDayIsTheWorlds() {
        world.run("time set 1000");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 30));
        String morning = agent.mustCall("get-world-state", Map.of("bot", BotWorld.BOT));

        world.run("time set 14000");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 30));
        String night = agent.mustCall("get-world-state", Map.of("bot", BotWorld.BOT));
        world.run("time set 1000");

        assertTrue(morning.startsWith("time: 07:0"), morning);
        assertTrue(morning.contains(" of the day, day)"), morning);
        assertTrue(morning.contains("weather: clear"), morning);
        assertTrue(night.startsWith("time: 20:0"), night);
        assertTrue(night.contains(" of the day, night)"), night);
    }

    /**
     * A plugin names paper after a quest and gives it a model of its own, and its lore is where the
     * instructions go. An inventory that said "paper x1" could not tell that note from any other
     * sheet, and one that dropped the blank lore line read a spaced tooltip as a different one.
     */
    @Test
    void aCarriedItemReadsWithItsLoreAndItsModel() {
        world.run("function mcagents:setup");
        agent.mustCall("wait-for-item", Map.of("bot", BotWorld.BOT, "pattern", "Quest Note", "timeoutMs", 10000));

        String inventory = agent.mustCall("list-inventory", Map.of("bot", BotWorld.BOT));
        String found = agent.mustCall("find-item", Map.of("bot", BotWorld.BOT, "nameOrType", "Quest Note"));
        String held = world.run("data get entity " + BotWorld.BOT + " Inventory[{id:\"minecraft:paper\"}].components");

        assertTrue(held.contains("\"hyperfarm:quest/note\""), held);
        assertTrue(inventory.contains("Quest Note [paper] x1 (slot ") && inventory.contains(
            ", model hyperfarm:quest/note)\n    Bring this to the smith\n    "), inventory);
        assertTrue(inventory.contains("- diamond x3 (slot 36)\n"), "a plain stack gained notes: " + inventory);
        /* The untrusted-content notice goes at the end of the first line, before the lore. */
        assertTrue(found.contains(" (model hyperfarm:quest/note).") && found.contains("\n    Bring this to the smith"), found);
    }

    /**
     * A cooldown is the server's, and it says so with a packet nothing else reads. Thrown in survival
     * so that the snowball the server takes says the use was accepted, which is what starts it.
     */
    @Test
    void anItemThatWasJustUsedIsCoolingDown() {
        agent.requires("use-held-item", "equip-item", "list-inventory");
        world.run("clear " + BotWorld.BOT);
        world.run("give " + BotWorld.BOT
            + " snowball[use_cooldown={seconds:60,cooldown_group:\"mcagents:probe\"}] 4");
        agent.mustCall("wait-for-item", Map.of("bot", BotWorld.BOT, "pattern", "snowball", "timeoutMs", 10000));
        agent.mustCall("equip-item", Map.of("bot", BotWorld.BOT, "itemName", "snowball"));
        world.run("gamemode survival " + BotWorld.BOT);
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));

        agent.mustCall("use-held-item", Map.of("bot", BotWorld.BOT));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        String inventory = agent.mustCall("list-inventory", Map.of("bot", BotWorld.BOT));
        String left = world.run("execute if items entity " + BotWorld.BOT + " weapon.mainhand minecraft:snowball[count=3]");

        world.run("gamemode creative " + BotWorld.BOT);
        world.run("function mcagents:setup");

        Matcher ticks = Pattern.compile("snowball x3 \\(slot \\d+, cooling down for (\\d+) more ticks\\)").matcher(inventory);
        assertTrue(left.startsWith("Test passed"), "the server did not take the snowball: " + left);
        assertTrue(ticks.find(), inventory);
        assertTrue(Integer.parseInt(ticks.group(1)) > 1000 && Integer.parseInt(ticks.group(1)) <= 1200, inventory);
    }

    /**
     * A click outside the window drops the cursor: one item for the right button and the rest for
     * the left. Asked of the ground and of the bot's inventory, since the cursor the client shows is
     * its own guess, and a cursor still holding something when the window closes goes back there.
     */
    @Test
    void aClickOutsideTheWindowDropsWhatTheCursorHolds() {
        world.run("function mcagents:setup");
        world.run("tp " + BotWorld.BOT + " 2 -59 0");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));
        agent.mustCall("open-container", Map.of("bot", BotWorld.BOT, "x", 1, "y", -60, "z", 3));

        agent.mustCall("click-slot", Map.of("bot", BotWorld.BOT, "slot", 4));
        String one = agent.mustCall("click-slot", Map.of("bot", BotWorld.BOT, "outside", true, "button", "right"));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));
        /* Counted before the rest follows it down, because two stacks on the ground merge into one. */
        String single = world.run(
            "execute if entity @e[type=item,nbt={Item:{id:\"minecraft:cooked_beef\",count:1}}]");

        String rest = agent.mustCall("click-slot", Map.of("bot", BotWorld.BOT, "outside", true));
        String refused = agent.refusal("click-slot", Map.of("bot", BotWorld.BOT, "outside", true, "slot", 4));
        agent.mustCall("close-window", Map.of("bot", BotWorld.BOT));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));

        String kept = world.run("execute if items entity " + BotWorld.BOT + " container.* minecraft:cooked_beef");
        String chest = world.run("data get block 1 -60 3 Items[{Slot:4b}]");
        world.run("function mcagents:setup");

        assertEquals("Right-clicked outside the window.\n  cursor: cooked_beef x12 -> cooked_beef x11", one);
        assertEquals("Left-clicked outside the window.\n  cursor: cooked_beef x11 -> empty", rest);
        assertTrue(refused.contains("takes no slot"), refused);
        assertTrue(single.startsWith("Test passed"), "one item did not reach the ground: " + single);
        assertTrue(kept.startsWith("Test failed"), "the rest came back to the inventory: " + kept);
        assertTrue(chest.startsWith("Found no elements"), chest);
    }

    /**
     * With a chest open the server takes clicks for the chest only, and drops a click meant for the
     * player's own inventory without a word. equip-item used to answer "Equipped" all the same.
     */
    @Test
    void equippingBehindAnOpenWindowIsRefused() {
        world.run("function mcagents:setup");
        agent.mustCall("wait-for-item", Map.of("bot", BotWorld.BOT, "pattern", "bow", "timeoutMs", 10000));
        agent.mustCall("open-container", Map.of("bot", BotWorld.BOT, "x", 1, "y", -60, "z", 3));

        String refused = agent.refusal("equip-item",
            Map.of("bot", BotWorld.BOT, "itemName", "bow", "destination", "off-hand"));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        agent.mustCall("close-window", Map.of("bot", BotWorld.BOT));

        String held = world.run("execute if items entity " + BotWorld.BOT + " weapon.offhand minecraft:bow");
        world.run("function mcagents:setup");

        assertTrue(refused.contains("Close it with close-window first."), refused);
        assertTrue(held.startsWith("Test failed"), "the bow moved after all: " + held);
    }

    /**
     * What the bot rides, and what it sits on: a plugin seats a player on an invisible entity riding
     * the mount. Asked of the server as well, which is the one that says who rides what.
     */
    @Test
    void theBotSaysWhatItIsRidingAndWhatItSitsOn() {
        world.run("kill @e[tag=mcagents_ride]");
        world.run("tp " + BotWorld.BOT + " 3 -60 -6");
        world.run("summon pig 3 -60 -7 {Tags:[\"mcagents_ride\"],NoAI:1b,Silent:1b,Invulnerable:1b,"
            + "Passengers:[{id:\"minecraft:armor_stand\",Tags:[\"mcagents_ride\",\"mcagents_seat\"],Invisible:1b}]}");
        world.run("summon minecart 5 -60 -7 {Tags:[\"mcagents_ride\",\"mcagents_cart\"]}");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        world.run("ride " + BotWorld.BOT + " mount @e[type=armor_stand,tag=mcagents_seat,limit=1]");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        String seated = agent.mustCall("get-player-state", Map.of("bot", BotWorld.BOT));
        String onSeat = world.run("execute as " + BotWorld.BOT + " on vehicle if entity @s[type=armor_stand]");
        String onPig = world.run("execute as " + BotWorld.BOT + " on vehicle on vehicle if entity @s[type=pig]");

        world.run("ride " + BotWorld.BOT + " dismount");
        world.run("ride " + BotWorld.BOT + " mount @e[type=minecart,tag=mcagents_cart,limit=1]");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        String carted = agent.mustCall("get-player-state", Map.of("bot", BotWorld.BOT));

        world.run("ride " + BotWorld.BOT + " dismount");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        String walking = agent.mustCall("get-player-state", Map.of("bot", BotWorld.BOT));
        world.run("kill @e[tag=mcagents_ride]");

        assertTrue(onSeat.startsWith("Test passed") && onPig.startsWith("Test passed"), onSeat + " / " + onPig);
        assertTrue(Pattern.compile("\nriding: pig \\(id \\d+\\), seated on armor_stand \\(id \\d+\\)$")
            .matcher(seated).find(), seated);
        assertTrue(Pattern.compile("\nriding: minecart \\(id \\d+\\)$").matcher(carted).find(), carted);
        assertTrue(!walking.contains("riding:"), walking);
    }

    /**
     * Furniture is displays, and every one is called item_display or block_display. What tells a
     * chair from a signpost is what it shows, so that is read, and checked against the entity the
     * server holds.
     */
    @Test
    void aDisplayIsFoundWithWhatItShows() {
        world.run("function mcagents:setup");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String item = agent.mustCall("find-entity",
            Map.of("bot", BotWorld.BOT, "type", "item_display", "maxDistance", 16));
        String block = agent.mustCall("find-entity",
            Map.of("bot", BotWorld.BOT, "type", "block_display", "maxDistance", 16));
        String model = world.run("data get entity @e[type=item_display,tag=mcagents,limit=1] item.components");
        String state = world.run("data get entity @e[type=block_display,tag=mcagents,limit=1] block_state");

        assertTrue(model.contains("\"hyperfarm:furniture/chair\""), model);
        assertTrue(state.contains("facing: \"east\""), state);
        assertTrue(item.contains("- item_display at (3, -58, -3), ")
            && item.contains(", showing Probe Chair [paper] x1 (model hyperfarm:furniture/chair)"), item);
        assertTrue(block.contains(
            ", showing oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"), block);
    }

    /**
     * The below-name slot is spelled below_name by the game, and one kind of bot looked it up as
     * belowName and found nothing there, whatever the server had put in it.
     */
    @Test
    void theBelowNameSlotIsRead() {
        world.run("scoreboard objectives remove mcagents_below");
        world.run("scoreboard objectives add mcagents_below dummy \"Probe Below\"");
        world.run("scoreboard objectives setdisplay below_name mcagents_below");
        world.run("scoreboard players set " + BotWorld.BOT + " mcagents_below 5");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String board = agent.mustCall("read-scoreboard", Map.of("bot", BotWorld.BOT, "slot", "belowName"));
        String score = world.run("scoreboard players get " + BotWorld.BOT + " mcagents_below");
        world.run("scoreboard objectives remove mcagents_below");

        assertTrue(score.contains("has 5"), score);
        assertTrue(board.startsWith("scoreboard \"Probe Below\" (belowName, 1 entries)"), board);
        assertTrue(board.contains("  " + BotWorld.BOT + ": 5"), board);
    }

    /**
     * An action bar a plugin holds up is the same packet every few ticks, and it is one thing
     * showing. The bot folds the repeats into one line; sent as a line each, twenty of them pushed
     * everything else out of what a caller reads and woke a waiter on a line that was already up.
     */
    @Test
    void anActionBarHeldUpIsOneLineShownManyTimes() {
        world.run("scoreboard objectives remove mcagents_fold");
        world.run("scoreboard objectives add mcagents_fold dummy");
        world.run("scoreboard players set #fold mcagents_fold 20");
        world.run("function mcagents:fold");
        sleep(3000);

        String bars = agent.mustCall("read-action-bar", Map.of("bot", BotWorld.BOT, "count", 5));
        world.run("scoreboard players set #fold mcagents_fold 0");
        world.run("scoreboard objectives remove mcagents_fold");

        assertEquals(1, Pattern.compile("Fold probe").matcher(bars).results().count(), bars);
        assertTrue(Pattern.compile("Fold probe \\(shown ([2-9]|\\d\\d+) times").matcher(bars).find(), bars);
    }

    /**
     * What the server counted for a player, from the fixture plugin's objectives: a client shows a
     * jump or a slot it never sent as readily as one it did, so a case about input asks the server.
     */
    private int counted(String objective) {
        String score = world.run("scoreboard players get " + BotWorld.BOT + " " + objective);
        Matcher has = Pattern.compile("has (-?\\d+) ").matcher(score);
        return has.find() ? Integer.parseInt(has.group(1)) : 0;
    }

    private void forget(String... objectives) {
        for (String objective : objectives) {
            world.run("scoreboard players reset " + BotWorld.BOT + " " + objective);
        }
    }

    /**
     * The jump tool moves the player and presses nothing, and a game that turns a page on the jump
     * key hears nothing from it. Two presses with the key up between them are two rising edges.
     */
    @Test
    void aJumpKeyReachesTheServerAsTwoPresses() {
        forget("fx_jump");

        String pressed = agent.mustCall("press-input",
            Map.of("bot", BotWorld.BOT, "key", "jump", "repeat", 2, "intervalTicks", 4));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        /* Untrusted, because what an after or an until matched is server text: every answer carries the notice. */
        assertTrue(pressed.startsWith("Pressed jump 2 times, 4 ticks apart."), pressed);
        assertEquals(2, counted("fx_jump"));
    }

    /** A number key picks a slot, and the slot a server hears about is the one it keeps. */
    @Test
    void aHotbarKeySelectsTheSlotTheServerHolds() {
        agent.mustCall("press-input", Map.of("bot", BotWorld.BOT, "key", "hotbar", "slot", 0));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));

        String pressed = agent.mustCall("press-input", Map.of("bot", BotWorld.BOT, "key", "hotbar", "slot", 3));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        String kept = world.run("data get entity " + BotWorld.BOT + " SelectedItemSlot");

        assertTrue(pressed.startsWith("Pressed hotbar slot 3 once. Hotbar slot 3 is selected."), pressed);
        assertEquals(3, counted("fx_slot"));
        assertTrue(kept.endsWith(": 3"), kept);
    }

    /**
     * hyperfarm's NPCs talk on the action bar and are answered with keys: jump for the next page, a
     * hotbar slot for an answer, sneak to leave. The fixture plays one, and its tags are what the
     * server made of the keys.
     */
    @Test
    void aConversationIsDrivenByJumpAHotbarSlotAndSneak() {
        /* Leaving is a sneak, so a bot that cannot press one would be left in the conversation. */
        agent.requires("press-input", "wait-for-action-bar");
        /* An answer is a change of slot, and slot 1 must not already be the one selected. */
        agent.mustCall("press-input", Map.of("bot", BotWorld.BOT, "key", "hotbar", "slot", 3));

        world.run("fixture talk " + BotWorld.BOT);
        agent.mustCall("wait-for-action-bar", Map.of("bot", BotWorld.BOT, "pattern", "Fine day", "timeoutMs", 10000));
        agent.mustCall("press-input", Map.of("bot", BotWorld.BOT, "key", "jump"));
        agent.mustCall("wait-for-action-bar",
            Map.of("bot", BotWorld.BOT, "pattern", "What will you plant", "timeoutMs", 10000));
        agent.mustCall("press-input", Map.of("bot", BotWorld.BOT, "key", "hotbar", "slot", 1));
        String answered = agent.mustCall("wait-for-action-bar",
            Map.of("bot", BotWorld.BOT, "pattern", "it is", "timeoutMs", 10000));
        agent.mustCall("press-input", Map.of("bot", BotWorld.BOT, "key", "sneak"));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String tags = world.run("tag " + BotWorld.BOT + " list");

        assertTrue(answered.contains("Carrot it is"), answered);
        assertTrue(tags.contains("fixture_choice_1"), tags);
        assertTrue(!tags.contains("fixture_choice_0"), tags);
        assertTrue(tags.contains("fixture_talk_left"), tags);
    }

    /**
     * A bite is a splash sound and a right-click inside the forty ticks after it. Waiting on the feed
     * and then pressing is a round trip through this server and back, so the bot presses on the tick
     * the sound arrives.
     */
    @Test
    void aBiteIsCaughtOnTheTickItsSplashArrives() {
        /* A cast rod keeps a hook out until it is reeled in, which is a press. */
        agent.requires("press-input");
        forget("fx_catch", "fx_miss");
        /*
        Looking straight up. The bot keeps the rotation the case before left it with, and a cast at a
        chest within reach opens the chest instead of casting.
        */
        world.run("tp " + BotWorld.BOT + " 2 -59 0 0 -90");
        world.run("item replace entity " + BotWorld.BOT + " weapon.mainhand with minecraft:fishing_rod");
        agent.mustCall("wait-for-item", Map.of("bot", BotWorld.BOT, "pattern", "fishing_rod", "timeoutMs", 10000));

        agent.mustCall("press-input", Map.of("bot", BotWorld.BOT, "key", "use"));
        String caught = agent.mustCall("press-input", Map.of("bot", BotWorld.BOT, "key", "use",
            "after", Map.of("feed", "effect", "pattern", "entity\\.fishing_bobber\\.splash"), "timeoutMs", 10000));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        int catches = counted("fx_catch");
        int misses = counted("fx_miss");
        world.run("function mcagents:setup");

        assertTrue(caught.startsWith("Waited "), caught);
        assertTrue(caught.contains("for the effect feed to match /entity\\.fishing_bobber\\.splash/"
            + " (\"minecraft:entity.fishing_bobber.splash\"), then pressed use once."), caught);
        assertEquals(1, catches, "the server counted " + misses + " reel(s) outside the window");
    }

    /**
     * hyperfarm's gathering wants a left-click within two ticks of a cue at a moment nobody can predict,
     * which is shorter than any round trip through this server. Five rounds, and four have to land:
     * the reaction is the bot's own, on the tick the cue arrives. How many ticks each took, as the
     * server counted them, is in the answer either way, because that is the number a game's window
     * has to be compared against.
     */
    @Test
    void aTwoTickWindowIsCaughtOnTheTickItsCueArrives() {
        agent.requires("press-input");
        /* Looking straight up, so a creative click breaks nothing. */
        world.run("tp " + BotWorld.BOT + " 2 -59 0 0 -90");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));
        forget("fx_gather_hit", "fx_gather_miss", "fx_gather_early", "fx_gather_ticks", "fx_gather_ms");

        List<String> reactions = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            world.run("fixture gather " + BotWorld.BOT);
            agent.mustCall("press-input", Map.of("bot", BotWorld.BOT, "key", "attack",
                "after", Map.of("feed", "actionBar", "pattern", "JUST"), "timeoutMs", 10000));
            agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));
            /* Read raw: an unset score reads as 0 through counted, which is also the best reaction there is. */
            String ticks = world.run("scoreboard players get " + BotWorld.BOT + " fx_gather_ticks");
            assertTrue(ticks.contains(" has "), "round " + round + " recorded no click: " + ticks);
            reactions.add(counted("fx_gather_ticks") + "t/" + counted("fx_gather_ms") + "ms");
            forget("fx_gather_ticks", "fx_gather_ms");
        }
        String measured = "reactions " + reactions + ", " + counted("fx_gather_early") + " early";
        System.out.println(System.getProperty("e2e.bot.kind") + " gathering " + measured);

        assertTrue(counted("fx_gather_hit") >= 4, "fewer than 4 of 5 inside the window: " + measured);
    }

    /**
     * Clicks into the air until a line says to stop, which is how a fish on the line is fought. Every
     * click the answer reports is one the server received, and none after the line.
     */
    @Test
    void leftClicksStopWhenTheActionBarSaysSo() {
        /* Looking straight up, so a click in creative breaks nothing under the bot. */
        world.run("tp " + BotWorld.BOT + " 2 -59 0 0 -90");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 5));
        forget("fx_left");

        new Thread(() -> {
            sleep(1500);
            world.run("title " + BotWorld.BOT + " actionbar \"enough clicks\"");
        }).start();

        String clicked = agent.mustCall("press-input", Map.of("bot", BotWorld.BOT, "key", "attack",
            "repeat", 100, "intervalTicks", 2, "until", Map.of("feed", "actionBar", "pattern", "enough"),
            "timeoutMs", 20000));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        Matcher presses = Pattern.compile("Pressed attack (\\d+) times").matcher(clicked);
        assertTrue(presses.find(), clicked);
        assertTrue(clicked.contains("; stopped at " + presses.group(1) + " of 100 when the actionBar feed matched"
            + " /enough/ (\"enough clicks\")."), clicked);
        assertEquals(Integer.parseInt(presses.group(1)), counted("fx_left"), clicked);
    }

    /**
     * A click starts breaking a block and a held button finishes it. A bot's mouse is never grabbed,
     * and the client only keeps breaking under a grabbed one, so a held attack in survival started on
     * dirt and gave up the next tick. The server's world is what says the block went.
     */
    @Test
    void aHeldAttackBreaksTheBlockAClickOnlyStarts() {
        world.run("setblock 11 -60 2 minecraft:dirt");
        world.run("gamemode survival " + BotWorld.BOT);
        world.run("clear " + BotWorld.BOT);
        world.run("tp " + BotWorld.BOT + " 11.5 -60 0.5");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        agent.mustCall("look-at", Map.of("bot", BotWorld.BOT, "x", 11, "y", -60, "z", 2));

        agent.mustCall("press-input", Map.of("bot", BotWorld.BOT, "key", "attack"));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 20));
        String clicked = world.run("execute if block 11 -60 2 minecraft:dirt");

        agent.mustCall("press-input", Map.of("bot", BotWorld.BOT, "key", "attack", "holdTicks", 60));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        String held = world.run("execute if block 11 -60 2 minecraft:air");

        world.run("setblock 11 -60 2 minecraft:air");
        world.run("gamemode creative " + BotWorld.BOT);
        world.run("function mcagents:setup");

        assertTrue(clicked.startsWith("Test passed"), "one click broke the dirt: " + clicked);
        assertTrue(held.startsWith("Test passed"), "holding the button did not break the dirt: " + held);
    }

    /** A dialog takes the keys on either kind of bot, so a press under one is refused on both. */
    @Test
    void aKeyPressedUnderADialogIsRefused() {
        world.run("dialog show " + BotWorld.BOT + " mcagents:check");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));

        String refused = agent.refusal("press-input", Map.of("bot", BotWorld.BOT, "key", "jump"));
        world.run("dialog clear " + BotWorld.BOT);

        assertTrue(refused.contains("a window is open, and keys go to it rather than to the game"), refused);
    }

    /**
     * A right-click on a chest opens the chest and is spent there. Using the item as well after every
     * click on a block threw a snowball at the chest it opened; on grass, which does nothing with a
     * click, the snowball is thrown. Survival, because creative throws without using one up, and the
     * count the server keeps is the proof.
     */
    @Test
    void aChestTakesTheRightClickAndGrassPassesItOn() {
        world.run("function mcagents:setup");
        world.run("gamemode survival " + BotWorld.BOT);
        world.run("clear " + BotWorld.BOT);
        world.run("give " + BotWorld.BOT + " minecraft:snowball 16");
        agent.mustCall("wait-for-item", Map.of("bot", BotWorld.BOT, "pattern", "snowball", "timeoutMs", 10000));
        agent.mustCall("press-input", Map.of("bot", BotWorld.BOT, "key", "hotbar", "slot", 0));

        world.run("tp " + BotWorld.BOT + " 1.5 -60 1.0");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        agent.mustCall("look-at", Map.of("bot", BotWorld.BOT, "x", 1, "y", -60, "z", 3));
        agent.mustCall("press-input", Map.of("bot", BotWorld.BOT, "key", "use"));
        String window = agent.mustCall("wait-for-window",
            Map.of("bot", BotWorld.BOT, "titlePattern", "Probe Chest", "timeoutMs", 5000));
        agent.mustCall("close-window", Map.of("bot", BotWorld.BOT));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        String kept = world.run("clear " + BotWorld.BOT + " minecraft:snowball 0");

        world.run("tp " + BotWorld.BOT + " 11.5 -60 -0.5");
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        agent.mustCall("look-at", Map.of("bot", BotWorld.BOT, "x", 11, "y", -61, "z", -2));
        agent.mustCall("press-input", Map.of("bot", BotWorld.BOT, "key", "use"));
        agent.mustCall("wait-ticks", Map.of("bot", BotWorld.BOT, "ticks", 10));
        String thrown = world.run("clear " + BotWorld.BOT + " minecraft:snowball 0");

        world.run("gamemode creative " + BotWorld.BOT);
        world.run("kill @e[type=snowball]");
        world.run("function mcagents:setup");

        assertTrue(window.startsWith("window \"[gui/header] Probe Chest\""), window);
        assertTrue(kept.contains("Found 16 "), "the chest click threw a snowball as well: " + kept);
        assertTrue(thrown.contains("Found 15 "), "the click on grass threw nothing: " + thrown);
    }
}
