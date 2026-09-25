package kr.junhyung.mcagents.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import kr.junhyung.mcagents.catalog.Catalog;
import kr.junhyung.mcagents.catalog.ToolSpec;
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

    private String learn() {
        return learn(catalog.require("learn-custom-blocks"));
    }

    private String learn(ToolSpec spec) {
        return text(customBlocks.learn(spec, played.bot, Map.of("bot", "fab"), Progress.NONE));
    }

    /** The same tool with no time to spend, which is how a server with more states than one call fits behaves. */
    private ToolSpec outOfTime() {
        ToolSpec spec = catalog.require("learn-custom-blocks");

        return new ToolSpec(spec.name(), spec.group(), spec.description(), spec.route(), spec.kinds(),
                spec.exclusive(), spec.untrusted(), spec.structured(), spec.readOnly(), spec.destructive(),
                spec.needsWorld(), 0, spec.inputSchema(), spec.wireSchema(), spec.wireSchemaHash(),
                spec.watches(), spec.requires());
    }

    /**
     * A call that runs out of time keeps what is already known instead of replacing it with the
     * less it managed. Every call used to start from an empty dictionary and put its own in place
     * of whatever was there, so a server with more states than one deadline fits learned the same
     * first few hundred for ever, and a complete dictionary shrank to a partial one the moment
     * anything asked for a relearn.
     */
    @Test
    void aCallThatRunsOutOfTimeKeepsWhatIsAlreadyKnown() {
        played.worldEdit = true;
        played.customBlocks.put("2025summer:bar_table[facing=east]", "note_block[instrument=banjo,note=1,powered=false]");
        played.customBlocks.put("2025summer:bar_table[facing=north]", "note_block[instrument=banjo,note=2,powered=false]");
        learn();

        String again = learn(outOfTime());

        assertTrue(again.startsWith("2 of 2 custom block state(s) on paper:25565 are known. This call put down 0 in "), again);
        assertEquals(2, customBlocks.of(played.bot).size());
        assertEquals("2025summer:bar_table[facing=east]",
                customBlocks.of(played.bot).byAppearance("note_block[instrument=banjo,note=1,powered=false]").id());
    }

    /**
     * And the next call takes the states the last one did not reach, rather than the ones it did.
     * The states already known are not placed again beyond the handful that checks the looks still
     * hold, which is the whole point of carrying the dictionary forward.
     */
    @Test
    void aSecondCallLearnsWhatTheFirstDidNotReach() {
        played.worldEdit = true;
        played.customBlocks.put("many:first", "note_block[instrument=banjo,note=1,powered=false]");
        learn();
        played.customBlocks.put("many:second", "note_block[instrument=banjo,note=2,powered=false]");

        String again = learn();

        assertTrue(again.startsWith("2 of 2 custom block state(s) on paper:25565 are known. This call put down 1 in "), again);
        assertEquals("many:second",
                customBlocks.of(played.bot).byAppearance("note_block[instrument=banjo,note=2,powered=false]").id());
        assertEquals("many:first",
                customBlocks.of(played.bot).byAppearance("note_block[instrument=banjo,note=1,powered=false]").id());
    }

    /**
     * A reload hands the same names different vanilla states, and a dictionary from before it is
     * confidently wrong rather than short: read-region would name blocks that are not there. The
     * handful put down again at the start of every resumed call is what notices, and one of them
     * reading differently throws all of it away.
     */
    @Test
    void aReloadThatMovesTheLooksThrowsTheDictionaryAway() {
        played.worldEdit = true;
        played.customBlocks.put("2025summer:bar_table[facing=east]", "note_block[instrument=banjo,note=1,powered=false]");
        learn();
        played.customBlocks.put("2025summer:bar_table[facing=east]", "note_block[instrument=bell,note=9,powered=false]");

        String again = learn();

        assertTrue(again.contains("did not look the way it did"), again);
        assertEquals(1, customBlocks.of(played.bot).size());
        assertEquals("2025summer:bar_table[facing=east]",
                customBlocks.of(played.bot).byAppearance("note_block[instrument=bell,note=9,powered=false]").id());
        assertNull(customBlocks.of(played.bot).byAppearance("note_block[instrument=banjo,note=1,powered=false]"));
    }

    /** A block taken out of the plugin goes with it: an entry for it answers for something that is not there. */
    @Test
    void aStateTheServerNoLongerNamesIsDropped() {
        played.worldEdit = true;
        played.customBlocks.put("2025summer:bar_table[facing=east]", "note_block[instrument=banjo,note=1,powered=false]");
        played.customBlocks.put("2025summer:lamp", "note_block[instrument=banjo,note=2,powered=false]");
        learn();
        played.customBlocks.remove("2025summer:lamp");

        learn();

        assertEquals(1, customBlocks.of(played.bot).size());
        assertNull(customBlocks.of(played.bot).byId("2025summer:lamp"));
        assertNull(customBlocks.of(played.bot).byAppearance("note_block[instrument=banjo,note=2,powered=false]"));
    }

    @Test
    void everyCustomBlockStateIsLearnedFromWhatItLooksLikeAndWhatItIsFiledAs() {
        played.worldEdit = true;
        played.customBlocks.put("2025summer:bar_table", "note_block[instrument=banjo,note=0,powered=false]");
        played.customBlocks.put("2025summer:bar_table[facing=east]", "note_block[instrument=banjo,note=1,powered=false]");
        played.customBlocks.put("2025summer:bar_table[facing=north]", "note_block[instrument=banjo,note=0,powered=false]");
        played.customBlocks.put("2025summer:lily", "air");

        String learned = text(customBlocks.learn(catalog.require("learn-custom-blocks"), played.bot, Map.of("bot", "fab"), Progress.NONE));

        assertTrue(learned.startsWith("3 of 3 custom block state(s) on paper:25565 are known. This call put down 3 in "), learned);
        assertTrue(learned.contains("2 showed the look a client sees them as, 1 placed as nothing"), learned);
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

        assertTrue(learned.startsWith("701 of 701 custom block state(s)"), learned);
        assertEquals(701, customBlocks.of(played.bot).size());
        assertEquals("many:block_699", customBlocks.of(played.bot).byInternal("craftengine:custom_800").id());
        assertEquals("few:one", customBlocks.of(played.bot).byInternal("craftengine:custom_100").id());
    }

    /** Put down through WorldEdit, a custom block goes by its own name, which the plugin's parser accepts as a pattern. */
    @Test
    void aLearnedCustomBlockIsPutDownByItsName() {
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

        assertTrue(played.ran.contains("//set city_road:road_line_1"), String.join("\n", played.ran));
        assertTrue(put.contains("Read back: all 1 blocks are as the region has them."), put);
    }

    /** Without WorldEdit to list the namespaces, the first page is what there is, and the answer says so. */
    @Test
    void withoutWorldEditTheNamespacesComeFromTheFirstPageAndTheAnswerSaysSo() {
        played.customBlocks.put("2025summer:bar_table[facing=east]", "note_block[instrument=banjo,note=1,powered=false]");

        String learned = text(customBlocks.learn(catalog.require("learn-custom-blocks"), played.bot, Map.of("bot", "fab"), Progress.NONE));

        assertTrue(learned.startsWith("1 of 1 custom block state(s)"), learned);
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
