package kr.junhyung.mcagents.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import kr.junhyung.mcagents.render.RegionRenderer;
import org.junit.jupiter.api.Test;

/**
 * The dense box, and the two directions runs cross it in. What matters is that a tile laid in and
 * a window taken out agree with the run order read-region promises, which is the one thing a
 * snapshot exists to hold still.
 */
class SnapshotTest {

    private static final Instant WHEN = Instant.parse("2026-09-22T00:00:00Z");

    private static RegionRenderer.View.Run run(int block, int count) {
        return new RegionRenderer.View.Run(block, count);
    }

    /** Two tiles with palettes in different orders land in one palette, and every block where its tile put it. */
    @Test
    void tilesAreLaidWhereTheyBelongAndTheirPalettesMerged() {
        Region box = new Region(0, 0, 0, 3, 0, 1);
        Snapshot kept = Snapshot.blank("r-0001", null, box, WHEN, "paper:25565")
                .with(new Region(0, 0, 0, 1, 0, 1), List.of("stone", "air"), List.of(run(0, 1), run(1, 1), run(0, 2)))
                .with(new Region(2, 0, 0, 3, 0, 1), List.of("air", "gold_block", "stone"), List.of(run(0, 1), run(1, 1), run(2, 2)));

        assertEquals(List.of("stone", "air", "gold_block"), kept.palette());
        assertEquals("stone", kept.blockAt(0, 0, 0));
        assertEquals("air", kept.blockAt(1, 0, 0));
        assertEquals("stone", kept.blockAt(0, 0, 1));
        assertEquals("stone", kept.blockAt(1, 0, 1));
        assertEquals("air", kept.blockAt(2, 0, 0));
        assertEquals("gold_block", kept.blockAt(3, 0, 0));
        assertEquals("stone", kept.blockAt(2, 0, 1));
        assertEquals(0, kept.unread());
    }

    /** A window's runs come out in the order they went in, so the renderer draws the same picture read-region did. */
    @Test
    void aWindowIsReadBackInTheOrderTheRunsCameIn() {
        Region box = new Region(10, 64, 20, 14, 66, 20);
        List<RegionRenderer.View.Run> arch = List.of(run(0, 1), run(1, 3), run(0, 2), run(1, 3), run(0, 6));
        Snapshot kept = Snapshot.blank("r-0002", "arch", box, WHEN, "paper:25565")
                .with(box, List.of("stone", "air"), arch);

        RegionRenderer.View view = kept.view(box);

        assertEquals(arch, view.runs());
        assertEquals(List.of("stone", "air"), view.palette());
        assertEquals(15, view.blocks());

        RegionRenderer.View sill = kept.view(new Region(11, 64, 20, 13, 64, 20));

        assertEquals(List.of("air"), sill.palette());
        assertEquals(List.of(run(0, 3)), sill.runs());
    }

    /** What a tile never said about stays unread, and is drawn as such rather than as a hole. */
    @Test
    void unreadBlocksAreNamedInAWindowAndNeverCounted() {
        Region box = new Region(0, 0, 0, 1, 0, 0);
        Snapshot kept = Snapshot.blank("r-0003", null, box, WHEN, "paper:25565")
                .with(new Region(0, 0, 0, 0, 0, 0), List.of("stone"), List.of(run(0, 1)));

        assertEquals(1, kept.unread());
        assertNull(kept.blockAt(1, 0, 0));
        assertEquals(List.of("stone", "(unread)"), kept.view(box).palette());
        assertEquals(1, kept.counts()[0]);
    }

    /** A run stream that does not tile its box is refused whole, and the array is left as it was. */
    @Test
    void runsThatDoNotTileTheTileAreRefusedBeforeAnythingIsLaid() {
        Region box = new Region(0, 0, 0, 1, 0, 0);
        Snapshot kept = Snapshot.blank("r-0004", null, box, WHEN, "paper:25565");

        assertThrows(IllegalArgumentException.class, () -> kept.with(box, List.of("stone"), List.of(run(0, 1))));
        assertThrows(IllegalArgumentException.class, () -> kept.with(box, List.of("stone"), List.of(run(0, 3))));
        assertThrows(IllegalArgumentException.class, () -> kept.with(box, List.of("stone"), List.of(run(1, 2))));
        assertEquals(2, kept.unread());
    }

    @Test
    void movingTheBoxMovesEveryBlockWithIt() {
        Region box = new Region(0, 0, 0, 1, 0, 0);
        Snapshot kept = Snapshot.blank("r-0005", null, box, WHEN, "paper:25565")
                .with(box, List.of("stone", "air"), List.of(run(0, 1), run(1, 1)));

        Snapshot moved = kept.movedTo(100, -60, 7);

        assertEquals(new Region(100, -60, 7, 101, -60, 7), moved.box());
        assertEquals("stone", moved.blockAt(100, -60, 7));
        assertEquals("air", moved.blockAt(101, -60, 7));
    }
}
