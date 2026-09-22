package kr.junhyung.mcagents.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Random;
import kr.junhyung.mcagents.render.RegionRenderer;
import org.junit.jupiter.api.Test;

/**
 * The boxes cover every block that is to be put down, once each, and no block that is not: that
 * is the property, and the count is what it buys.
 */
class MeshTest {

    private static Snapshot solid(Region box, String block) {
        return Snapshot.blank("r-0001", null, box, Instant.EPOCH, "test")
                .with(box, List.of(block), List.of(new RegionRenderer.View.Run(0, (int) box.blocks())));
    }

    @Test
    void aFloorIsOneCommand() {
        Region floor = new Region(0, 64, 0, 63, 64, 63);

        List<Mesh.Box> boxes = Mesh.boxes(solid(floor, "stone"), floor, false);

        assertEquals(1, boxes.size());
        assertEquals(floor, boxes.getFirst().box());
    }

    /** A slab past what one /fill takes is cut at the limit, not sent whole to be refused. */
    @Test
    void aBoxStopsGrowingAtWhatOneFillTakes() {
        Region slab = new Region(0, 0, 0, 63, 8, 63);

        List<Mesh.Box> boxes = Mesh.boxes(solid(slab, "stone"), slab, false);

        assertEquals(2, boxes.size());
        assertEquals(Mesh.MAX_FILL, boxes.get(0).box().blocks());
        assertEquals(64 * 64, boxes.get(1).box().blocks());
    }

    @Test
    void airIsLeftOutUnlessAskedForAndUnreadAlways() {
        Region box = new Region(0, 0, 0, 2, 0, 0);
        Snapshot kept = Snapshot.blank("r-0001", null, box, Instant.EPOCH, "test")
                .with(new Region(0, 0, 0, 1, 0, 0), List.of("stone", "air"),
                        List.of(new RegionRenderer.View.Run(0, 1), new RegionRenderer.View.Run(1, 1)));

        assertEquals(1, Mesh.boxes(kept, box, false).size());
        assertEquals(2, Mesh.boxes(kept, box, true).size());
    }

    /** Whatever the shape, every block to be put down is in exactly one box, and nothing else is. */
    @Test
    void theBoxesCoverEveryBlockOnceAndNoOther() {
        Random random = new Random(7);
        Region box = new Region(-5, 10, 3, 14, 19, 22);
        int[] noise = new int[(int) box.blocks()];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = random.nextInt(3);
        }
        List<RegionRenderer.View.Run> runs = new java.util.ArrayList<>();
        for (int i = 0; i < noise.length; i++) {
            runs.add(new RegionRenderer.View.Run(noise[i], 1));
        }
        Snapshot kept = Snapshot.blank("r-0001", null, box, Instant.EPOCH, "test")
                .with(box, List.of("stone", "air", "oak_planks"), runs);

        List<Mesh.Box> boxes = Mesh.boxes(kept, box, false);
        int[] covered = new int[noise.length];

        for (Mesh.Box piece : boxes) {
            for (int y = piece.box().minY(); y <= piece.box().maxY(); y++) {
                for (int z = piece.box().minZ(); z <= piece.box().maxZ(); z++) {
                    for (int x = piece.box().minX(); x <= piece.box().maxX(); x++) {
                        assertEquals(piece.block(), kept.at(x, y, z));
                        covered[kept.index(x, y, z)]++;
                    }
                }
            }
        }
        for (int i = 0; i < noise.length; i++) {
            assertEquals(noise[i] == 1 ? 0 : 1, covered[i], "block " + i);
        }
        assertTrue(boxes.size() < noise.length * 2 / 3, "greedy boxes should merge some of " + noise.length);
        assertFalse(boxes.isEmpty());
    }
}
