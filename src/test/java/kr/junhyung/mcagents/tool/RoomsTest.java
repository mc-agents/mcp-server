package kr.junhyung.mcagents.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The shapes a measurement has to tell apart.
 *
 * <p>The arcade is the one this was built for. Reading a town hall by flooding the air that had
 * something above it gave one blob of the whole building, because the building is roofed and open
 * -- and every attempt to find the room in it by geometry failed until the test moved from "is
 * there a roof" to "do the walls stop it".
 */
class RoomsTest {

    private static final int SIZE = 24;

    /** A box of air with whatever the test builds into it, as a snapshot a measurement can read. */
    private static final class Build {

        private final Region box = new Region(0, 0, 0, SIZE - 1, 7, SIZE - 1);
        private final List<String> palette = new ArrayList<>(List.of("air", "stone"));
        private final short[] blocks = new short[SIZE * 8 * SIZE];

        Build set(int x, int y, int z, String block) {
            int entry = palette.indexOf(block);

            if (entry < 0) {
                palette.add(block);
                entry = palette.size() - 1;
            }
            blocks[(y * SIZE + z) * SIZE + x] = (short) entry;
            return this;
        }

        /** Four walls, a floor and a roof around a box of air, with a gap for a door. */
        Build room(int fromX, int fromZ, int toX, int toZ, boolean walls) {
            for (int x = fromX; x <= toX; x++) {
                for (int z = fromZ; z <= toZ; z++) {
                    set(x, 0, z, "stone");
                    set(x, 5, z, "stone");
                }
            }
            if (!walls) {
                return this;
            }
            for (int y = 1; y < 5; y++) {
                for (int x = fromX; x <= toX; x++) {
                    set(x, y, fromZ, "stone");
                    set(x, y, toZ, "stone");
                }
                for (int z = fromZ; z <= toZ; z++) {
                    set(fromX, y, z, "stone");
                    set(toX, y, z, "stone");
                }
            }
            return this;
        }

        /** Pillars holding the roof up and nothing between them, which is what an arcade is. */
        Build pillars(int fromX, int fromZ, int toX, int toZ) {
            for (int y = 1; y < 5; y++) {
                for (int x = fromX; x <= toX; x += 4) {
                    for (int z = fromZ; z <= toZ; z += 4) {
                        set(x, y, z, "stone");
                    }
                }
            }
            return this;
        }

        Snapshot snapshot() {
            return new Snapshot("r-test", "test", box, palette, blocks, Instant.EPOCH, null);
        }

        Region box() {
            return box;
        }
    }

    @Test
    void aWalledRoomIsMeasuredAtItsWalls() {
        Build built = new Build().room(4, 4, 12, 12, true);
        Rooms.Room room = Rooms.of(built.snapshot(), built.box(), new int[] {8, 2, 8}, 0.75, 2);

        assertNotNull(room);
        /* Exactly the air the walls hold: seven by seven, four deep, and not a block past them. */
        assertEquals(new Region(5, 1, 5, 11, 4, 11), room.box(), "the room is the air the walls hold");
        /*
        Not all 196 of it. The ring against the walls has a wall one block away, which is the same
        thing a doorway has, so those blocks are entered and reported as where the room ends rather
        than carrying it further -- and the four corners behind them are never reached at all.
        */
        assertTrue(room.cells().size() > 7 * 7 * 4 * 3 / 4,
                "the room came to %d blocks".formatted(room.cells().size()));
        assertTrue(room.cells().size() < 7 * 7 * 4, "the ring against the walls should not spread");
    }

    /**
     * The failure this exists for. Everything under the roof is air with something above it, so the
     * old rule takes the whole floor; the new one takes nothing, because an arcade's blocks see no
     * walls along most of their horizon.
     */
    @Test
    void anOpenArcadeIsNotARoomAndSaysSoByStoppingAtOnce() {
        Build built = new Build().room(2, 2, 21, 21, false).pillars(2, 2, 21, 21);
        Rooms.Room room = Rooms.of(built.snapshot(), built.box(), new int[] {11, 2, 11}, 0.75, 2);

        assertNotNull(room);
        /*
        The seed and its six neighbours and nothing else: the seed always spreads, so seven is what
        "it went nowhere" looks like, against the four hundred blocks of floor the old rule took.
        */
        assertEquals(7, room.cells().size(),
                "an arcade carried the room %d blocks".formatted(room.cells().size()));
        assertEquals(6, room.frontier().size(), "every block it entered should have been a dead end");
    }

    /**
     * Two rooms sharing a doorway are two rooms. The fill goes into the doorway -- it is air and
     * you can stand in it -- and stops there, because a doorway is one block wide and has no room
     * around it. The block it stopped on is the doorway, found without looking for it.
     */
    @Test
    void aDoorwayIsEnteredAndNotPassedThrough() {
        Build built = new Build().room(2, 2, 10, 10, true).room(10, 2, 18, 10, true);

        for (int y = 1; y < 4; y++) {
            built.set(10, y, 6, "air");
        }
        Snapshot snapshot = built.snapshot();
        Rooms.Room room = Rooms.of(snapshot, built.box(), new int[] {6, 2, 6}, 0.75, 2);

        assertNotNull(room);
        assertTrue(room.box().maxX() <= 11,
                "the fill went through the doorway to x=%d".formatted(room.box().maxX()));
        assertTrue(!room.frontier().isEmpty(), "the doorway was not reported as where the room ends");
    }

    /**
     * A point on something solid is what an agent gives when it aims at a table rather than at the
     * air over it, so the nearest block the room's air runs through is measured from instead.
     */
    @Test
    void aPointInsideABlockIsMovedToTheNearestOpenOne() {
        Build built = new Build().room(4, 4, 12, 12, true).set(8, 1, 8, "stone");
        Rooms.Room room = Rooms.of(built.snapshot(), built.box(), new int[] {8, 1, 8}, 0.75, 2);

        assertNotNull(room);
        assertTrue(room.movedSeed(), "the seed was on a block and was not moved off it");
        assertEquals(new Region(5, 1, 5, 11, 4, 11), room.box(), "it measured the room around the block");
    }
}
