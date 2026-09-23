package kr.junhyung.mcagents.tool;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a room ends, measured from a point inside it.
 *
 * <p>The obvious way does not work. Marking air that has something above it as "indoors" and
 * flooding that gives one blob of twenty thousand blocks for an open arcade, because a roof is
 * everywhere and what makes a room is walls. The same shape read that way leaks out of every side
 * of the box and answers nothing.
 *
 * <p>So this measures sideways instead, and puts the test on whether a cell may spread rather than
 * on what a cell is. A cell is entered when it is open; it spreads only when it is enclosed --
 * most of its horizon runs into a wall -- and when it has room around it. One rule, three results:
 * a fill cannot escape into an arcade, because an arcade's cells see no walls; it cannot slip
 * through a door into the next room, because a doorway has no room around it; and the cells it
 * entered but could not spread from are the doorways and windows, already found.
 */
final class Rooms {

    /** How far a horizon is looked along before it counts as running into nothing. */
    private static final int REACH = 24;

    /** The eight ways a horizon is measured: the four sides and the four corners between them. */
    private static final int[][] HORIZON = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** How big a box this is worth doing on; past it the caller narrows the window. */
    static final long MAX_BLOCKS = 4_194_304;

    /** How far from a solid seed a breathable block is looked for. */
    private static final int SNAP = 3;

    private Rooms() {
    }

    /** What was found, in the terms the answer is written in. */
    record Room(Region box, List<int[]> cells, List<int[]> frontier, int[] seed, boolean movedSeed,
            int[] touches, long unread, double clearanceUsed) {}

    /**
     * The room around a point.
     *
     * @param enclosure how much of a cell's horizon must run into a wall for it to spread
     * @param clearance how far the nearest wall must be for it to spread
     */
    static Room of(Snapshot snapshot, Region box, int[] at, double enclosure, int clearance) {
        int sizeX = (int) box.sizeX();
        int sizeY = (int) box.sizeY();
        int sizeZ = (int) box.sizeZ();
        boolean[] open = new boolean[sizeX * sizeY * sizeZ];
        long unread = 0;

        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    String block = snapshot.blockAt(box.minX() + x, box.minY() + y, box.minZ() + z);

                    if (block == null) {
                        unread++;
                    }
                    open[at(x, y, z, sizeX, sizeZ)] = Blocks.open(block);
                }
            }
        }
        int[] seed = breathable(open, box, at, sizeX, sizeY, sizeZ);

        if (seed == null) {
            return null;
        }
        byte[] enclosed = enclosure(open, sizeX, sizeY, sizeZ);
        short[] room = clearance(open, sizeX, sizeY, sizeZ);

        /* A cupboard is narrower than the threshold and cannot leak anyway, so the seed sets the floor. */
        double wanted = Math.min(clearance, room[at(seed[0], seed[1], seed[2], sizeX, sizeZ)]);

        return fill(open, enclosed, room, box, seed, at, sizeX, sizeY, sizeZ,
                enclosure * HORIZON.length, wanted, unread);
    }

    private static int at(int x, int y, int z, int sizeX, int sizeZ) {
        return (y * sizeZ + z) * sizeX + x;
    }

    /** The seed, moved to the nearest block a fill can stand in when the caller aimed at a wall. */
    private static int[] breathable(boolean[] open, Region box, int[] at, int sizeX, int sizeY, int sizeZ) {
        int x = at[0] - box.minX();
        int y = at[1] - box.minY();
        int z = at[2] - box.minZ();

        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            return null;
        }
        if (open[at(x, y, z, sizeX, sizeZ)]) {
            return new int[] {x, y, z, 0};
        }
        for (int reach = 1; reach <= SNAP; reach++) {
            for (int dy = -reach; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    for (int dx = -reach; dx <= reach; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        int nz = z + dz;

                        if (nx < 0 || ny < 0 || nz < 0 || nx >= sizeX || ny >= sizeY || nz >= sizeZ) {
                            continue;
                        }
                        if (open[at(nx, ny, nz, sizeX, sizeZ)]) {
                            return new int[] {nx, ny, nz, 1};
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * How many of the eight horizons each cell has that end in a wall.
     *
     * <p>Swept and not cast. Along one direction the answer at a cell is one more than the answer
     * behind it, so each direction is a single ordered pass over the array rather than a ray per
     * cell -- which is what makes this affordable on a box of four million.
     *
     * <p>Only sideways. A low ceiling blocks every upward direction, so counting those would make
     * every cramped arcade read as a room, which is the failure this whole thing is built around.
     */
    private static byte[] enclosure(boolean[] open, int sizeX, int sizeY, int sizeZ) {
        byte[] enclosed = new byte[open.length];

        for (int[] step : HORIZON) {
            short[] distance = new short[open.length];
            int fromX = step[0] > 0 ? 0 : sizeX - 1;
            int toX = step[0] > 0 ? sizeX : -1;
            int byX = step[0] > 0 ? 1 : -1;
            int fromZ = step[1] > 0 ? 0 : sizeZ - 1;
            int toZ = step[1] > 0 ? sizeZ : -1;
            int byZ = step[1] > 0 ? 1 : -1;

            for (int y = 0; y < sizeY; y++) {
                for (int z = fromZ; z != toZ; z += byZ) {
                    for (int x = fromX; x != toX; x += byX) {
                        int here = at(x, y, z, sizeX, sizeZ);

                        if (!open[here]) {
                            distance[here] = 0;
                            continue;
                        }
                        int backX = x - step[0];
                        int backZ = z - step[1];

                        /* Off the edge of the box: the horizon ran out rather than ran into a wall. */
                        if (backX < 0 || backZ < 0 || backX >= sizeX || backZ >= sizeZ) {
                            distance[here] = REACH;
                            continue;
                        }
                        distance[here] = (short) Math.min(REACH, 1 + distance[at(backX, y, backZ, sizeX, sizeZ)]);
                    }
                }
            }
            for (int cell = 0; cell < open.length; cell++) {
                if (open[cell] && distance[cell] < REACH) {
                    enclosed[cell]++;
                }
            }
        }
        return enclosed;
    }

    /** How far the nearest wall is on the four sides, which is what a doorway has none of. */
    private static short[] clearance(boolean[] open, int sizeX, int sizeY, int sizeZ) {
        short[] room = new short[open.length];

        java.util.Arrays.fill(room, (short) REACH);

        for (int[] step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            short[] distance = new short[open.length];
            int fromX = step[0] > 0 ? 0 : sizeX - 1;
            int toX = step[0] > 0 ? sizeX : -1;
            int byX = step[0] > 0 ? 1 : -1;
            int fromZ = step[1] > 0 ? 0 : sizeZ - 1;
            int toZ = step[1] > 0 ? sizeZ : -1;
            int byZ = step[1] > 0 ? 1 : -1;

            for (int y = 0; y < sizeY; y++) {
                for (int z = fromZ; z != toZ; z += byZ) {
                    for (int x = fromX; x != toX; x += byX) {
                        int here = at(x, y, z, sizeX, sizeZ);

                        if (!open[here]) {
                            distance[here] = 0;
                            continue;
                        }
                        int backX = x - step[0];
                        int backZ = z - step[1];

                        distance[here] = backX < 0 || backZ < 0 || backX >= sizeX || backZ >= sizeZ
                                ? REACH
                                : (short) Math.min(REACH, 1 + distance[at(backX, y, backZ, sizeX, sizeZ)]);
                    }
                }
            }
            for (int cell = 0; cell < open.length; cell++) {
                room[cell] = (short) Math.min(room[cell], distance[cell]);
            }
        }
        return room;
    }

    private static Room fill(boolean[] open, byte[] enclosed, short[] room, Region box, int[] seed,
            int[] asked, int sizeX, int sizeY, int sizeZ, double enclosure, double clearance, long unread) {
        boolean[] seen = new boolean[open.length];
        List<int[]> cells = new ArrayList<>();
        List<int[]> frontier = new ArrayList<>();
        Deque<int[]> queue = new ArrayDeque<>();
        int[] touches = new int[6];

        queue.add(new int[] {seed[0], seed[1], seed[2]});
        seen[at(seed[0], seed[1], seed[2], sizeX, sizeZ)] = true;

        while (!queue.isEmpty()) {
            int[] cell = queue.poll();
            int here = at(cell[0], cell[1], cell[2], sizeX, sizeZ);

            cells.add(cell);

            boolean spreads = cells.size() == 1
                    || (enclosed[here] >= enclosure && room[here] >= clearance);

            if (!spreads) {
                frontier.add(cell);
                continue;
            }
            if (cell[0] == 0) touches[0]++;
            if (cell[0] == sizeX - 1) touches[1]++;
            if (cell[1] == 0) touches[2]++;
            if (cell[1] == sizeY - 1) touches[3]++;
            if (cell[2] == 0) touches[4]++;
            if (cell[2] == sizeZ - 1) touches[5]++;

            for (int[] step : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                int nx = cell[0] + step[0];
                int ny = cell[1] + step[1];
                int nz = cell[2] + step[2];

                if (nx < 0 || ny < 0 || nz < 0 || nx >= sizeX || ny >= sizeY || nz >= sizeZ) {
                    continue;
                }
                int next = at(nx, ny, nz, sizeX, sizeZ);

                if (seen[next] || !open[next]) {
                    continue;
                }
                seen[next] = true;
                queue.add(new int[] {nx, ny, nz});
            }
        }
        Region bounds = bounds(cells, box);

        return new Room(bounds, cells, frontier,
                new int[] {seed[0] + box.minX(), seed[1] + box.minY(), seed[2] + box.minZ()},
                seed[3] == 1 && (seed[0] + box.minX() != asked[0] || seed[1] + box.minY() != asked[1]
                        || seed[2] + box.minZ() != asked[2]),
                touches, unread, clearance);
    }

    private static Region bounds(List<int[]> cells, Region box) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (int[] cell : cells) {
            minX = Math.min(minX, cell[0]);
            maxX = Math.max(maxX, cell[0]);
            minY = Math.min(minY, cell[1]);
            maxY = Math.max(maxY, cell[1]);
            minZ = Math.min(minZ, cell[2]);
            maxZ = Math.max(maxZ, cell[2]);
        }
        return new Region(minX + box.minX(), minY + box.minY(), minZ + box.minZ(),
                maxX + box.minX(), maxY + box.minY(), maxZ + box.minZ());
    }

    /**
     * The shell the room actually shows: the blocks with a face onto it, and not the stone behind
     * them. What a room is finished in is the inside of its walls, which is what a builder chose.
     */
    static Map<String, long[]> shell(Snapshot snapshot, List<int[]> cells, Region box) {
        boolean[] counted = new boolean[(int) box.blocks()];
        Map<String, long[]> facing = new LinkedHashMap<>();

        for (int[] cell : cells) {
            int x = cell[0] + box.minX();
            int y = cell[1] + box.minY();
            int z = cell[2] + box.minZ();

            for (int[] step : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                int nx = x + step[0];
                int ny = y + step[1];
                int nz = z + step[2];

                if (!box.contains(nx, ny, nz)) {
                    continue;
                }
                int index = snapshot.index(nx, ny, nz);

                if (counted[index]) {
                    continue;
                }
                String block = snapshot.blockAt(nx, ny, nz);

                if (block == null || Blocks.open(block)) {
                    continue;
                }
                counted[index] = true;

                /* Which face of the room it is: under it, over it, or around it. */
                long[] where = facing.computeIfAbsent(block, name -> new long[3]);

                where[step[1] > 0 ? 1 : step[1] < 0 ? 0 : 2]++;
            }
        }
        return facing;
    }

    /** The shell blocks a builder put there to see or walk through, most of them first. */
    static List<Map.Entry<String, Long>> openings(Map<String, long[]> shell) {
        Map<String, Long> found = new LinkedHashMap<>();

        shell.forEach((block, faces) -> {
            if (Blocks.opening(block)) {
                found.put(block, faces[0] + faces[1] + faces[2]);
            }
        });
        return found.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, Long> entry) -> entry.getValue()).reversed())
                .toList();
    }
}
