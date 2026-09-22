package kr.junhyung.mcagents.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegionStoreTest {

    private static Snapshot boxOf(String id, long blocks) {
        return Snapshot.blank(id, null, new Region(0, 0, 0, (int) blocks - 1, 0, 0), Instant.EPOCH, "test");
    }

    /** Full means the oldest goes, as many as it takes and no more, and the answer says which. */
    @Test
    void theOldestGoesWhenANewRegionNeedsTheRoom() {
        RegionStore store = new RegionStore();

        assertEquals(List.of(), store.keep(boxOf("r-0001", RegionStore.MAX_BLOCKS / 2)));
        assertEquals(List.of(), store.keep(boxOf("r-0002", RegionStore.MAX_BLOCKS / 4)));
        assertEquals(List.of(), store.keep(boxOf("r-0003", RegionStore.MAX_BLOCKS / 4)));
        assertEquals(RegionStore.MAX_BLOCKS, store.held());

        assertEquals(List.of("r-0001"), store.keep(boxOf("r-0004", RegionStore.MAX_BLOCKS / 4)));
        assertNull(store.get("r-0001"));
        assertEquals(List.of("r-0002", "r-0003", "r-0004"), store.all().stream().map(Snapshot::id).toList());

        assertEquals(List.of("r-0002", "r-0003", "r-0004"), store.keep(boxOf("r-0005", RegionStore.MAX_BLOCKS)));
        assertEquals(RegionStore.MAX_BLOCKS, store.held());
    }

    @Test
    void aRegionLargerThanTheWholeStoreIsRefused() {
        RegionStore store = new RegionStore();

        assertThrows(IllegalArgumentException.class, () -> store.keep(boxOf("r-0001", RegionStore.MAX_BLOCKS + 1)));
        assertEquals(0, store.held());
    }

    @Test
    void anUnknownIdIsRefusedInTermsThatSayWhereItWent() {
        RegionStore store = new RegionStore();
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> store.require("r-dead"));

        assertTrue(refused.getMessage().contains("no region is kept as \"r-dead\""), refused.getMessage());
        assertTrue(refused.getMessage().contains("restarts"), refused.getMessage());
    }

    @Test
    void freshIdsHaveTheShapeTheToolsQuote() {
        RegionStore store = new RegionStore();
        String id = store.fresh();

        assertTrue(id.matches("r-[0-9a-f]{4}"), id);
    }
}
