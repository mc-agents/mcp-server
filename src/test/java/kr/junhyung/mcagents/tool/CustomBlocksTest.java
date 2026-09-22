package kr.junhyung.mcagents.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import kr.junhyung.mcagents.catalog.Catalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The dictionary learned from a server that plays CraftEngine: every state listed, placed, read
 * back as the look it wears, and filed under the id the plugin gives it -- and then a region read
 * on that server naming the custom blocks rather than their looks.
 */
class CustomBlocksTest {

    private final Catalog catalog = Catalog.load();

    private PlayedWorld played;
    private CustomBlocks customBlocks;
    private RegionSurvey survey;

    @BeforeEach
    void link() throws IOException {
        played = new PlayedWorld(catalog);

        RemoteTools remote = new RemoteTools(catalog);
        Commands commands = new Commands(remote, catalog);
        customBlocks = new CustomBlocks(remote, commands, catalog);
        survey = new RegionSurvey(remote, commands, new RegionStore(), catalog, customBlocks);
    }

    @AfterEach
    void unlink() throws IOException {
        played.close();
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    private static Map<String, Object> corner(int x, int y, int z) {
        return Map.of("x", x, "y", y, "z", z);
    }

    @Test
    void everyCustomBlockStateIsLearnedFromWhatItLooksLikeAndWhatItIsFiledAs() {
        played.worldEdit = true;
        played.customBlocks.put("2025summer:bar_table", "note_block[instrument=banjo,note=0,powered=false]");
        played.customBlocks.put("2025summer:bar_table[facing=east]", "note_block[instrument=banjo,note=1,powered=false]");
        played.customBlocks.put("2025summer:bar_table[facing=north]", "note_block[instrument=banjo,note=0,powered=false]");
        played.customBlocks.put("2025summer:lily", "air");

        String learned = text(customBlocks.learn(catalog.require("learn-custom-blocks"), played.bot, Map.of("bot", "fab"), Progress.NONE));

        assertTrue(learned.startsWith("Learned 3 custom block state(s) on paper:25565 in "), learned);
        assertTrue(learned.contains("2 with the look a client sees them as, 1 that placed as nothing"), learned);
        /* The row was placed beside the bot at the top of the world, and cleared. */
        assertEquals(List.of("fill -27 319 5 -25 319 5 air"), played.commands("fill"));
        assertEquals("air", played.world.getOrDefault(PlayedWorld.key(-27, 319, 5), "air"));

        CustomBlocks.Dictionary dictionary = customBlocks.of(played.bot);

        assertEquals(3, dictionary.size());
        assertEquals("2025summer:bar_table[facing=east]", dictionary.byAppearance("note_block[instrument=banjo,note=1,powered=false]").id());
        assertEquals("craftengine:custom_101", dictionary.byId("2025summer:bar_table[facing=east]").internal());
        assertEquals("2025summer:bar_table[facing=east]", dictionary.byInternal("craftengine:custom_101").id());
        assertNull(dictionary.byId("2025summer:lily").appearance());
        assertNull(dictionary.byAppearance("stone"));
        /* The bare name is the default state; only the states themselves are learned. */
        assertNull(dictionary.byId("2025summer:bar_table"));
    }

    @Test
    void aRegionReadWhereTheBlocksAreLearnedNamesThemRatherThanTheirLooks() {
        played.worldEdit = true;
        played.customBlocks.put("2025summer:bar_table[facing=east]", "note_block[instrument=banjo,note=1,powered=false]");
        customBlocks.learn(catalog.require("learn-custom-blocks"), played.bot, Map.of("bot", "fab"), Progress.NONE);
        played.world.put(PlayedWorld.key(1, 64, 1), "note_block[instrument=banjo,note=1,powered=false]");
        played.world.put(PlayedWorld.key(2, 64, 1), "note_block[instrument=banjo,note=7,powered=false]");

        String read = text(survey.read(catalog.require("read-region"), played.bot, Map.of("bot", "fab",
                "from", corner(1, 64, 1), "to", corner(2, 64, 1)), Progress.NONE));

        assertTrue(read.contains("1 2025summer:bar_table[facing=east] (50%)"), read);
        assertTrue(read.contains("1 note_block[instrument=banjo,note=7,powered=false] (50%)"), read);
    }

    /**
     * A server completes at most a page at a time, and its completion is a search, not a prefix:
     * a dictionary learned from the first page alone was missing every block after it. The names
     * are asked for by namespace and narrowed by the path's first characters until every page is
     * whole, with the namespaces read off WorldEdit's pattern completion.
     */
    @Test
    void moreCustomBlocksThanOnePageCompletesAreAllLearned() {
        played.worldEdit = true;
        played.customBlocks.put("few:one", "note_block[instrument=harp,note=24,powered=true]");
        for (int i = 0; i < 700; i++) {
            played.customBlocks.put("many:block_%03d".formatted(i),
                    "note_block[instrument=%s,note=%d,powered=%s]".formatted(i % 2 == 0 ? "harp" : "bass", i % 25, i % 50 < 25));
        }

        String learned = text(customBlocks.learn(catalog.require("learn-custom-blocks"), played.bot, Map.of("bot", "fab"), Progress.NONE));

        assertTrue(learned.startsWith("Learned 701 custom block state(s)"), learned);
        assertEquals(701, customBlocks.of(played.bot).size());
        assertEquals("many:block_699", customBlocks.of(played.bot).byInternal("craftengine:custom_800").id());
        assertEquals("few:one", customBlocks.of(played.bot).byInternal("craftengine:custom_100").id());
    }

    /**
     * Put down through WorldEdit, a learned custom block goes by the id the plugin filed it under:
     * a name with its state is a pattern too, but a single-state block's bare name is not one.
     */
    @Test
    void aLearnedCustomBlockIsPutDownByTheIdWorldEditFilesItUnder() {
        played.worldEdit = true;
        played.customBlocks.put("city_road:road_line_1", "note_block[instrument=bell,note=2,powered=false]");
        customBlocks.learn(catalog.require("learn-custom-blocks"), played.bot, Map.of("bot", "fab"), Progress.NONE);
        Region box = new Region(0, 64, 0, 0, 64, 0);
        RegionStore store = new RegionStore();
        RemoteTools remote = new RemoteTools(catalog);
        Commands commands = new Commands(remote, catalog);
        RegionSurvey writing = new RegionSurvey(remote, commands, store, catalog, customBlocks);
        store.keep(Snapshot.blank("r-cb01", null, box, java.time.Instant.EPOCH, "import-region")
                .with(box, List.of("city_road:road_line_1"), List.of(new kr.junhyung.mcagents.render.RegionRenderer.View.Run(0, 1))));

        String put = text(writing.write(catalog.require("write-region"), played.bot, Map.of("bot", "fab", "region", "r-cb01"), Progress.NONE));

        assertTrue(played.ran.contains("//set craftengine:custom_100"), String.join("\n", played.ran));
        assertTrue(put.contains("Read back: all 1 blocks are as the region has them."), put);
    }

    /** Without WorldEdit to list the namespaces, the first page is what there is, and the answer says so. */
    @Test
    void withoutWorldEditTheNamespacesComeFromTheFirstPageAndTheAnswerSaysSo() {
        played.customBlocks.put("2025summer:bar_table[facing=east]", "note_block[instrument=banjo,note=1,powered=false]");

        String learned = text(customBlocks.learn(catalog.require("learn-custom-blocks"), played.bot, Map.of("bot", "fab"), Progress.NONE));

        assertTrue(learned.startsWith("Learned 1 custom block state(s)"), learned);
        assertTrue(learned.contains("The namespaces were read off the first page of names"), learned);
    }

    @Test
    void aServerWithoutCraftEngineIsSaidSoAndNothingIsPlaced() {
        McpSchema.CallToolResult learned = customBlocks.learn(catalog.require("learn-custom-blocks"), played.bot,
                Map.of("bot", "fab"), Progress.NONE);

        assertTrue(learned.isError());
        assertTrue(text(learned).contains("either CraftEngine is not on it"), text(learned));
        assertEquals(List.of(), played.ran);
        assertNull(customBlocks.of(played.bot));
    }
}
