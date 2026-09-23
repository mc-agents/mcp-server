package kr.junhyung.mcagents.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import kr.junhyung.mcagents.catalog.Catalog;
import org.junit.jupiter.api.Test;

/** A region spelled out by hand, kept, listed and drawn, with nothing but the store behind it. */
class StoredRegionsTest {

    private final Catalog catalog = Catalog.load();
    private final RegionStore store = new RegionStore();
    private final RemoteTools remote = new RemoteTools(catalog);
    private final CustomBlocks customBlocks =
            new CustomBlocks(remote, new Commands(remote, catalog), catalog);
    private final StoredRegions regions = new StoredRegions(store, customBlocks);

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    private static Map<String, Object> corner(int x, int y, int z) {
        return Map.of("x", x, "y", y, "z", z);
    }

    @Test
    void anImportedRegionIsDrawnKeptAndListed() {
        String imported = text(regions.call(catalog.require("import-region"), Map.of(
                "at", corner(10, 64, 20), "size", corner(5, 3, 1), "name", "arch",
                "palette", List.of("stone", "air"),
                "runs", List.of(Map.of("block", 0, "count", 1), Map.of("block", 1, "count", 3), Map.of("block", 0, "count", 2),
                        Map.of("block", 1, "count", 3), Map.of("block", 0, "count", 6)))));

        assertTrue(imported.startsWith("(10, 64, 20) to (14, 66, 20), 5 x 3 x 1, 15 blocks.\n  a  9 stone (60%)\n  .  6 air (40%)"), imported);
        assertTrue(imported.contains("\n  y=64\n    a...a\n  y=65\n    a...a\n  y=66\n    aaaaa"), imported);
        assertTrue(imported.contains("Kept as region r-"), imported);

        Snapshot kept = store.all().getFirst();
        String listed = text(regions.call(catalog.require("list-regions"), Map.of()));

        assertTrue(listed.startsWith("1 region(s) kept, 15 blocks of the 16777216 this server holds at once, oldest first:"), listed);
        assertTrue(listed.contains("  " + kept.id() + " (\"arch\"): (10, 64, 20) to (14, 66, 20), 5 x 3 x 1, 15 blocks, 2 kinds of block, from import-region, "), listed);

        String shown = text(regions.call(catalog.require("show-region"), Map.of("region", kept.id(),
                "from", corner(10, 66, 20), "to", corner(14, 66, 20))));

        assertTrue(shown.startsWith("Region " + kept.id() + " (\"arch\"), window: (10, 66, 20) to (14, 66, 20), 5 x 1 x 1, 5 blocks.\n  a  5 stone (100%)"), shown);
        assertTrue(shown.contains("\n  y=66\n    aaaaa"), shown);
    }

    @Test
    void runsThatDoNotSpellTheBoxOutAreRefusedWithTheCount() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> regions.call(catalog.require("import-region"), Map.of(
                        "at", corner(0, 0, 0), "size", corner(2, 1, 1), "palette", List.of("stone"),
                        "runs", List.of(Map.of("block", 0, "count", 3)))));

        assertTrue(refused.getMessage().contains("spell out 3 of the 2 blocks"), refused.getMessage());
        assertTrue(store.all().isEmpty());
    }

    @Test
    void aWindowOutsideTheRegionOrTooBigToDrawIsRefused() {
        Region box = new Region(0, 0, 0, 63, 1, 63);
        store.keep(Snapshot.blank("r-big0", null, box, java.time.Instant.EPOCH, "test")
                .with(box, List.of("stone"), List.of(new kr.junhyung.mcagents.render.RegionRenderer.View.Run(0, 64 * 64 * 2))));

        IllegalArgumentException outside = assertThrows(IllegalArgumentException.class,
                () -> regions.call(catalog.require("show-region"), Map.of("region", "r-big0",
                        "from", corner(0, 0, 0), "to", corner(64, 0, 0))));
        assertTrue(outside.getMessage().contains("reaches outside region r-big0"), outside.getMessage());

        IllegalArgumentException big = assertThrows(IllegalArgumentException.class,
                () -> regions.call(catalog.require("show-region"), Map.of("region", "r-big0")));
        assertTrue(big.getMessage().contains("holds 8192 blocks, and show-region draws at most 4096"), big.getMessage());

        assertEquals("Region r-big0, window: (0, 1, 0) to (63, 1, 63), 64 x 1 x 64, 4096 blocks.\n  a  4096 stone (100%)",
                text(regions.call(catalog.require("show-region"), Map.of("region", "r-big0",
                        "from", corner(0, 1, 0), "to", corner(63, 1, 63)))).lines().limit(2).reduce((a, b) -> a + "\n" + b).orElse(""));
    }
}
