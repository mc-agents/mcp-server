package kr.junhyung.mcagents.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.junhyung.mcagents.bot.BotSession;
import kr.junhyung.mcagents.catalog.Catalog;
import kr.junhyung.mcagents.catalog.ToolSpec;
import kr.junhyung.mcagents.render.RegionRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The walk, against a bot that plays a world: which tiles the bot is sent to, what it is asked to
 * read, what is laid where, what is put down through which command, and what is read back.
 */
class RegionSurveyTest {

    private final Catalog catalog = Catalog.load();
    private final RegionStore store = new RegionStore();

    private PlayedWorld played;
    private BotSession bot;
    private RegionSurvey survey;

    @BeforeEach
    void link() throws IOException {
        played = new PlayedWorld(catalog);
        bot = played.bot;

        RemoteTools remote = new RemoteTools(catalog);
        Commands commands = new Commands(remote, catalog);
        survey = new RegionSurvey(remote, commands, store, catalog, new CustomBlocks(remote, commands, catalog));
    }

    @AfterEach
    void unlink() throws IOException {
        played.close();
    }

    private static Map<String, Object> corner(int x, int y, int z) {
        return Map.of("x", x, "y", y, "z", z);
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    private List<String> commands(String word) {
        return played.commands(word);
    }

    /**
     * Two tiles wide and one call tall: the bot holds neither where it stands, is sent to the middle
     * of each in turn, reads it, and is put back. What it read is one region with both tiles' blocks
     * where the world had them and one palette across both.
     */
    @Test
    void aBoxPastOneCallIsWalkedTileByTileAndKeptWhole() {
        played.fill(new Region(200, 64, 200, 263, 71, 263), "stone");
        played.fill(new Region(264, 64, 200, 327, 71, 263), "dirt");
        played.world.put(PlayedWorld.key(300, 70, 250), "gold_block");

        ToolSpec spec = catalog.require("read-region");
        String read = text(survey.read(spec, bot, Map.of("bot", "fab", "name", "yard",
                "from", corner(200, 64, 200), "to", corner(327, 71, 263)), Progress.NONE));

        assertEquals(List.of("tp 232 72 232", "tp 296 72 232", "tp 5.50 64.00 5.50"), commands("tp"));
        assertTrue(read.startsWith("(200, 64, 200) to (327, 71, 263), 128 x 8 x 64, 65536 blocks.\n"
                + "  32768 stone (50%)\n  32767 dirt (50%)\n      1 gold_block (0%)"), read);
        assertTrue(read.contains("No map: 65536 blocks is more than the 4096 one is drawn for."), read);
        assertTrue(read.contains("Kept as region r-"), read);
        assertTrue(read.contains("(\"yard\"), read in 2 tile(s) in "), read);
        assertFalse(read.contains("unread"), read);

        Snapshot kept = store.all().getFirst();

        assertEquals(0, kept.unread());
        assertEquals("gold_block", kept.blockAt(300, 70, 250));
        assertEquals("stone", kept.blockAt(263, 64, 263));
        assertEquals("dirt", kept.blockAt(264, 64, 263));
    }

    /** A box the bot already holds is read where it stands, and the bot is not moved at all. */
    @Test
    void aBoxTheBotAlreadyHoldsIsReadWithoutMovingIt() {
        played.fill(new Region(0, 64, 0, 40, 64, 40), "stone");

        String read = text(survey.read(catalog.require("read-region"), bot, Map.of("bot", "fab",
                "from", corner(0, 64, 0), "to", corner(40, 71, 40)), Progress.NONE));

        assertEquals(List.of(), commands("tp"));
        assertTrue(read.contains("Kept as region r-"), read);
        assertEquals(41L * 41 * 8, store.all().getFirst().blocks());
    }

    /**
     * A bot that may not /tp is told so in terms of what to do instead, and what it never reached is
     * left unread rather than read as air: the tile it stood in is in the region, the rest is not.
     */
    @Test
    void aBotThatMayNotTeleportIsToldSoAndTheRestIsLeftUnread() {
        played.saysAbout.put("tp", command -> List.of("Unknown command. Type \"/help\" for help."));

        String read = text(survey.read(catalog.require("read-region"), bot, Map.of("bot", "fab",
                "from", corner(200, 64, 200), "to", corner(327, 71, 263)), Progress.NONE));

        assertEquals(List.of("tp 232 72 232"), commands("tp"));
        assertTrue(read.contains("could not be teleported to (232, 72, 232): /tp came back as \"Unknown command."), read);
        assertTrue(read.contains("needs the permission to run /tp (op)"), read);
        assertTrue(read.contains("65536 (unread)"), read);
    }

    /**
     * Putting a region down is one /fill a box of the mesh, and the world read back is what the
     * region said. The gold block keeps the floor from being one command, which is what shows the
     * mesh was used rather than a /fill a block.
     */
    @Test
    void aKeptRegionIsPutDownWithOneFillABoxAndReadBack() {
        Region box = new Region(0, 64, 0, 3, 64, 3);
        List<RegionRenderer.View.Run> runs = List.of(new RegionRenderer.View.Run(0, 15), new RegionRenderer.View.Run(1, 1));
        store.keep(Snapshot.blank("r-f100", "floor", box, Instant.EPOCH, "import-region")
                .with(box, List.of("stone", "gold_block"), runs));

        String put = text(survey.write(catalog.require("write-region"), bot, Map.of("bot", "fab", "region", "r-f100", "via", "fill"),
                Progress.NONE));

        assertEquals(List.of("fill 0 64 0 3 64 2 stone", "fill 0 64 3 2 64 3 stone", "fill 3 64 3 3 64 3 gold_block"),
                commands("fill"));
        assertEquals(List.of(), commands("tp"));
        assertTrue(put.startsWith("Put region r-f100 down over (0, 64, 0) to (3, 64, 3), 4 x 1 x 4, 16 blocks: 16 blocks in 3 /fill command(s) across 1 tile(s)"), put);
        assertTrue(put.contains("Read back: all 16 blocks are as the region has them."), put);
        assertEquals("gold_block", played.world.get(PlayedWorld.key(3, 64, 3)));
    }

    /** Moved, with air put down too: the air clears what was there, and the readback says so when it did not. */
    @Test
    void aRegionPutDownElsewhereWithAirClearsWhatWasThereAndTheReadbackSaysWhatDiffers() {
        Region box = new Region(0, 64, 0, 1, 64, 0);
        store.keep(Snapshot.blank("r-f200", null, box, Instant.EPOCH, "import-region")
                .with(box, List.of("stone", "air"), List.of(new RegionRenderer.View.Run(0, 1), new RegionRenderer.View.Run(1, 1))));
        played.fill(new Region(10, 64, 10, 11, 64, 10), "dirt");
        /* Answered as landed, and not landing: what the readback is for. */
        played.saysAbout.put("fill", command -> List.of("Successfully filled 1 block(s)"));

        String put = text(survey.write(catalog.require("write-region"), bot, Map.of("bot", "fab", "region", "r-f200",
                "at", corner(10, 64, 10), "pasteAir", true, "via", "fill"), Progress.NONE));

        assertEquals(List.of("fill 10 64 10 10 64 10 stone", "fill 11 64 10 11 64 10 air"), commands("fill"));
        assertTrue(put.contains("air included"), put);
        assertTrue(put.contains("Read back: 2 of 2 blocks differ from the region, for example (10, 64, 10) wanted stone and is dirt; (11, 64, 10) wanted air and is dirt."), put);
    }

    /** The first /fill is where a bot without the permission finds out, and nothing else is sent after it. */
    @Test
    void aFirstFillThatIsRefusedStopsTheWriteAndSaysWhatItNeeds() {
        Region box = new Region(0, 64, 0, 3, 64, 3);
        store.keep(Snapshot.blank("r-f300", null, box, Instant.EPOCH, "import-region")
                .with(box, List.of("stone", "gold_block"), List.of(new RegionRenderer.View.Run(0, 15), new RegionRenderer.View.Run(1, 1))));
        played.saysAbout.put("fill", command -> List.of("Unknown command. Type \"/help\" for help."));

        McpSchema.CallToolResult put = survey.write(catalog.require("write-region"), bot,
                Map.of("bot", "fab", "region", "r-f300", "via", "fill"), Progress.NONE);

        assertTrue(put.isError());
        assertEquals(1, commands("fill").size());
        assertTrue(text(put).contains("needs the permission to run it (op)"), text(put));
    }

    /**
     * With WorldEdit answering, a region goes down through it: a selection and a //set a box, the
     * server's own "completed" line waited for before the readback, and a custom block -- one the
     * game's own /fill could only put down as its look-alike -- placed by its name.
     */
    @Test
    void withWorldEditARegionGoesDownThroughItCustomBlocksIncluded() {
        played.worldEdit = true;
        Region box = new Region(0, 64, 0, 3, 64, 3);
        store.keep(Snapshot.blank("r-we00", null, box, Instant.EPOCH, "import-region")
                .with(box, List.of("stone", "2025summer:bar_table[facing=east]"),
                        List.of(new RegionRenderer.View.Run(0, 15), new RegionRenderer.View.Run(1, 1))));

        String put = text(survey.write(catalog.require("write-region"), bot, Map.of("bot", "fab", "region", "r-we00"), Progress.NONE));

        assertEquals(List.of("//pos1 0,64,0", "//pos1 0,64,0", "//pos2 3,64,2", "//set stone",
                "//pos1 0,64,3", "//pos2 2,64,3", "//set stone",
                "//pos1 3,64,3", "//pos2 3,64,3", "//set 2025summer:bar_table[facing=east]"),
                played.ran.stream().filter(command -> command.startsWith("//")).toList());
        assertEquals(List.of(), commands("fill"));
        assertTrue(put.startsWith("Put region r-we00 down over (0, 64, 0) to (3, 64, 3), 4 x 1 x 4, 16 blocks: 16 blocks in 3 WorldEdit edit(s)"), put);
        assertTrue(put.contains("Read back: all 16 blocks are as the region has them."), put);
    }

    /**
     * With the selection readable on WorldEdit's CUI channel, that is what the corners are confirmed
     * on. The same commands are sent; what changes is that the edit waits for the plugin to say
     * what it has selected instead of for a line it printed, and the answer says which it was.
     */
    @Test
    void whereTheSelectionIsDescribedTheChannelConfirmsItRatherThanChat() {
        played.worldEdit = true;
        played.cui = true;
        Region box = new Region(0, 64, 0, 3, 64, 3);
        store.keep(Snapshot.blank("r-cui0", null, box, Instant.EPOCH, "import-region")
                .with(box, List.of("stone"), List.of(new RegionRenderer.View.Run(0, 16))));

        String put = text(survey.write(catalog.require("write-region"), bot, Map.of("bot", "fab", "region", "r-cui0"), Progress.NONE));

        /*
        Three commands and not four: the //pos1 that used to go first, to find out whether the
        server had WorldEdit at all, is not sent where the channel has already said so.
        */
        assertEquals(List.of("//pos1 0,64,0", "//pos2 3,64,3", "//set stone"),
                played.ran.stream().filter(command -> command.startsWith("//")).toList());
        assertTrue(put.contains("Each box was selected over WorldEdit's CUI channel"), put);
        assertTrue(put.contains("Read back: all 16 blocks are as the region has them."), put);
    }

    /**
     * The reason the channel is read at all. FastAsyncWorldEdit answers //pos1 on the tick and moves
     * the corner on a thread of its own, so a driver that takes the acknowledgement for the corner
     * sends the next //set over the box before it -- which is how a wall of stone once landed where
     * the previous box had been. Asking the plugin what it has selected is what makes that
     * impossible: the edit does not go until the corners are this box's.
     */
    @Test
    void aCornerThePluginMovesLateIsWaitedForRatherThanEditedOver() {
        played.worldEdit = true;
        played.cui = true;
        played.selectionLagMs = 120;
        Region box = new Region(0, 64, 0, 3, 64, 3);
        store.keep(Snapshot.blank("r-cui1", null, box, Instant.EPOCH, "import-region")
                .with(box, List.of("stone", "2025summer:bar_table[facing=east]"),
                        List.of(new RegionRenderer.View.Run(0, 15), new RegionRenderer.View.Run(1, 1))));

        String put = text(survey.write(catalog.require("write-region"), bot, Map.of("bot", "fab", "region", "r-cui1"), Progress.NONE));

        assertTrue(put.contains("16 blocks in 3 WorldEdit edit(s)"), put);
        assertTrue(put.contains("Read back: all 16 blocks are as the region has them."), put);
    }

    /**
     * The case the channel is there for, and a live server is one: WorldEdit runs every command and
     * the server passes none of its words on. Judged by chat alone that is a server without
     * WorldEdit, and the region went down through /fill -- which cannot place a custom block at
     * all, so the block was silently left out. The channel says WorldEdit is there, and the block
     * that appears says the edit is over.
     */
    @Test
    void aServerThatRunsWorldEditWithoutSayingSoStillGoesThroughIt() {
        played.worldEdit = true;
        played.cui = true;
        played.silent = true;
        Region box = new Region(0, 64, 0, 1, 64, 0);
        store.keep(Snapshot.blank("r-mute", null, box, Instant.EPOCH, "import-region")
                .with(box, List.of("stone", "2025summer:bar_table[facing=east]"),
                        List.of(new RegionRenderer.View.Run(0, 1), new RegionRenderer.View.Run(1, 1))));

        String put = text(survey.write(catalog.require("write-region"), bot, Map.of("bot", "fab", "region", "r-mute"), Progress.NONE));

        assertEquals(List.of("//pos1 0,64,0", "//pos2 0,64,0", "//set stone",
                "//pos1 1,64,0", "//pos2 1,64,0", "//set 2025summer:bar_table[facing=east]"),
                played.ran.stream().filter(command -> command.startsWith("//")).toList());
        assertEquals(List.of(), commands("fill"));
        assertTrue(put.contains("2 blocks in 2 WorldEdit edit(s)"), put);
        assertTrue(put.contains("Read back: all 2 blocks are as the region has them."), put);
    }

    /**
     * A bot that offers read-selection against a server that describes nothing falls back to chat,
     * and the answer says which of the two it used so that a write nobody watched can be explained.
     */
    @Test
    void aServerThatDescribesNoSelectionIsDrivenFromChatAndSaysSo() {
        played.worldEdit = true;
        Region box = new Region(0, 64, 0, 0, 64, 0);
        store.keep(Snapshot.blank("r-cui2", null, box, Instant.EPOCH, "import-region")
                .with(box, List.of("stone"), List.of(new RegionRenderer.View.Run(0, 1))));

        String put = text(survey.write(catalog.require("write-region"), bot, Map.of("bot", "fab", "region", "r-cui2"), Progress.NONE));

        assertTrue(put.contains("selected by reading what the plugin said in chat"), put);
        assertTrue(put.contains("Read back: all 1 blocks are as the region has them."), put);
    }

    /** Without WorldEdit, /fill is what there is, and a custom block is left out rather than faked. */
    @Test
    void withoutWorldEditACustomBlockIsLeftOutAndSaidSo() {
        Region box = new Region(0, 64, 0, 1, 64, 0);
        store.keep(Snapshot.blank("r-cb00", null, box, Instant.EPOCH, "import-region")
                .with(box, List.of("stone", "2025summer:bar_table[facing=east]"),
                        List.of(new RegionRenderer.View.Run(0, 1), new RegionRenderer.View.Run(1, 1))));

        String put = text(survey.write(catalog.require("write-region"), bot, Map.of("bot", "fab", "region", "r-cb00"), Progress.NONE));

        assertEquals(List.of("fill 0 64 0 0 64 0 stone"), commands("fill"));
        assertTrue(put.contains("1 of its blocks are custom blocks, which /fill cannot place"), put);
        assertTrue(put.contains("Read back: 1 of 2 blocks differ"), put);
    }

    /** Insisting on WorldEdit where there is none is refused, and nothing is put down. */
    @Test
    void insistingOnWorldEditWithoutItIsRefusedBeforeAnythingIsPutDown() {
        Region box = new Region(0, 64, 0, 0, 64, 0);
        store.keep(Snapshot.blank("r-we01", null, box, Instant.EPOCH, "import-region")
                .with(box, List.of("stone"), List.of(new RegionRenderer.View.Run(0, 1))));

        McpSchema.CallToolResult put = survey.write(catalog.require("write-region"), bot,
                Map.of("bot", "fab", "region", "r-we01", "via", "worldedit"), Progress.NONE);

        assertTrue(put.isError());
        assertTrue(text(put).contains("neither describes a selection on WorldEdit's CUI channel nor acknowledges //pos1"), text(put));
        assertEquals(List.of("//pos1 0,64,0"), played.ran);
    }

    /** A region nobody kept is refused before the bot is touched. */
    @Test
    void anUnknownRegionIsRefusedBeforeTheBotIsTouched() {
        IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> survey.write(catalog.require("write-region"), bot, Map.of("bot", "fab", "region", "r-none"), Progress.NONE));

        assertTrue(refused.getMessage().contains("no region is kept as \"r-none\""), refused.getMessage());
        assertEquals(List.of(), played.ran);
    }
}
