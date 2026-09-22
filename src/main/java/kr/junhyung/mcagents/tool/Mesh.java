package kr.junhyung.mcagents.tool;

import java.util.ArrayList;
import java.util.List;
import kr.junhyung.mcagents.render.RegionRenderer;

/**
 * A window of a snapshot as the fewest boxes of one block each, which is how it is put down: one
 * /fill a box.
 *
 * <p>Greedy, in the order the blocks are kept. From each block not yet covered a box grows along x
 * while the blocks match, then along z while whole rows match, then along y while whole slabs
 * match. A floor becomes one command, a wall one, a hollow room six; a checkerboard is still a
 * command a block, which is what a checkerboard costs. /fill takes 32768 blocks at most, and a box
 * stops growing before it would pass that.
 */
final class Mesh {

    /** What one /fill may change, which is the game's commandModificationBlockLimit as shipped. */
    static final int MAX_FILL = 32_768;

    record Box(Region box, short block) {}

    private Mesh() {}

    /**
     * @param window   the part of the snapshot to cover, inside its box
     * @param withAir  whether air is put down too; without it, air in the source leaves what is there
     */
    static List<Box> boxes(Snapshot snapshot, Region window, boolean withAir) {
        int width = (int) window.sizeX();
        int height = (int) window.sizeY();
        int depth = (int) window.sizeZ();
        boolean[] covered = new boolean[width * height * depth];
        List<Box> boxes = new ArrayList<>();

        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    if (covered[local(x, y, z, width, depth)]) {
                        continue;
                    }

                    short block = snapshot.at(window.minX() + x, window.minY() + y, window.minZ() + z);

                    if (block == Snapshot.UNREAD || (!withAir && RegionRenderer.isAir(snapshot.palette().get(block)))) {
                        continue;
                    }

                    int dx = 1;
                    while (x + dx < width && same(snapshot, window, covered, block, x + dx, y, z, 1, 1, 1)) {
                        dx++;
                    }

                    int dz = 1;
                    while (z + dz < depth && dx * (dz + 1) <= MAX_FILL
                            && same(snapshot, window, covered, block, x, y, z + dz, dx, 1, 1)) {
                        dz++;
                    }

                    int dy = 1;
                    while (y + dy < height && dx * dz * (dy + 1) <= MAX_FILL
                            && same(snapshot, window, covered, block, x, y + dy, z, dx, 1, dz)) {
                        dy++;
                    }

                    for (int cy = y; cy < y + dy; cy++) {
                        for (int cz = z; cz < z + dz; cz++) {
                            for (int cx = x; cx < x + dx; cx++) {
                                covered[local(cx, cy, cz, width, depth)] = true;
                            }
                        }
                    }

                    boxes.add(new Box(new Region(
                            window.minX() + x, window.minY() + y, window.minZ() + z,
                            window.minX() + x + dx - 1, window.minY() + y + dy - 1, window.minZ() + z + dz - 1), block));
                }
            }
        }
        return boxes;
    }

    /** Whether every block of a slab at a local offset is this block and not yet covered. */
    private static boolean same(Snapshot snapshot, Region window, boolean[] covered, short block,
            int x, int y, int z, int dx, int dy, int dz) {
        int width = (int) window.sizeX();
        int depth = (int) window.sizeZ();

        for (int cy = y; cy < y + dy; cy++) {
            for (int cz = z; cz < z + dz; cz++) {
                for (int cx = x; cx < x + dx; cx++) {
                    if (covered[local(cx, cy, cz, width, depth)]
                            || snapshot.at(window.minX() + cx, window.minY() + cy, window.minZ() + cz) != block) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static int local(int x, int y, int z, int width, int depth) {
        return (y * depth + z) * width + x;
    }
}
