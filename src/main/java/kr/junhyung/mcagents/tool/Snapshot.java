package kr.junhyung.mcagents.tool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.junhyung.mcagents.render.Point;
import kr.junhyung.mcagents.render.RegionRenderer;

/**
 * A box of blocks the server holds on to.
 *
 * <p>What a bot reads is answered in runs, and runs are the right thing to send and the wrong thing
 * to keep: a box read in tiles has to be assembled, a window of it drawn, and a copy of it put down
 * somewhere else, and every one of those wants the block at a position rather than the position of
 * a run. So a snapshot is dense -- one palette index a block, in the same y-then-z-then-x order the
 * runs come in -- and the runs are made again from it whenever something wants them.
 *
 * <p>A short a block: sixteen bits is a palette of thirty-two thousand kinds, and a world has a
 * few thousand. {@link #UNREAD} marks a block nothing ever said anything about, which is what a
 * tile the client never got leaves behind, and what a file that stores only some of its box leaves
 * behind too; it is never written back and never counted as a block.
 */
public final class Snapshot {

    static final short UNREAD = -1;

    /** How many kinds of block a palette may hold, which is what a short's positive range allows. */
    static final int MAX_PALETTE = Short.MAX_VALUE;

    private final String id;
    private final String name;
    private final Region box;
    private final List<String> palette;
    private final short[] blocks;
    private final Instant created;
    private final String origin;

    Snapshot(String id, String name, Region box, List<String> palette, short[] blocks, Instant created,
            String origin) {
        if (blocks.length != box.blocks()) {
            throw new IllegalArgumentException("%d blocks were given for a box of %d".formatted(blocks.length, box.blocks()));
        }
        if (palette.size() > MAX_PALETTE) {
            throw new IllegalArgumentException("a palette of %d kinds is more than the %d one holds"
                    .formatted(palette.size(), MAX_PALETTE));
        }
        this.id = id;
        this.name = name;
        this.box = box;
        this.palette = List.copyOf(palette);
        this.blocks = blocks;
        this.created = created;
        this.origin = origin;
    }

    /** An empty box, every block unread, to be filled tile by tile. */
    static Snapshot blank(String id, String name, Region box, Instant created, String origin) {
        short[] blocks = new short[(int) box.blocks()];

        java.util.Arrays.fill(blocks, UNREAD);

        return new Snapshot(id, name, box, List.of(), blocks, created, origin);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    Region box() {
        return box;
    }

    public List<String> palette() {
        return palette;
    }

    public Instant created() {
        return created;
    }

    /** Where it came from: the server it was read on, or the file it was given as. */
    public String origin() {
        return origin;
    }

    public long blocks() {
        return box.blocks();
    }

    /** The same box with the corners moved, which is what putting a copy down somewhere else means. */
    Snapshot movedTo(int x, int y, int z) {
        Region moved = new Region(x, y, z,
                x + (int) box.sizeX() - 1, y + (int) box.sizeY() - 1, z + (int) box.sizeZ() - 1);

        return new Snapshot(id, name, moved, palette, blocks, created, origin);
    }

    Snapshot named(String id, String name, String origin) {
        return new Snapshot(id, name, box, palette, blocks, created, origin);
    }

    /** The same blocks under other names, entry for entry: what a custom-block dictionary does to a palette. */
    Snapshot withPalette(List<String> names) {
        if (names.size() != palette.size()) {
            throw new IllegalArgumentException("%d names for a palette of %d".formatted(names.size(), palette.size()));
        }
        return new Snapshot(id, name, box, names, blocks, created, origin);
    }

    /** The palette index at a world position, or {@link #UNREAD}. */
    short at(int x, int y, int z) {
        return blocks[index(x, y, z)];
    }

    public String blockAt(int x, int y, int z) {
        short entry = at(x, y, z);

        return entry == UNREAD ? null : palette.get(entry);
    }

    int index(int x, int y, int z) {
        return (int) ((((long) y - box.minY()) * box.sizeZ() + (z - box.minZ())) * box.sizeX() + (x - box.minX()));
    }

    public long unread() {
        long unread = 0;

        for (short block : blocks) {
            if (block == UNREAD) {
                unread++;
            }
        }
        return unread;
    }

    /**
     * Lay a tile that was read into this box, remapping its palette into this one.
     *
     * <p>The tile's runs have to tile its box exactly -- no missing, no outside -- because a run
     * stream with a gap in it says nothing about where the blocks after the gap sit. That is the
     * caller's to have checked; a tile that fails it is left unread, which is the honest state.
     *
     * <p>In place: a box of four million blocks assembled from five hundred tiles cannot afford a
     * copy a tile, so the array is shared with the snapshot handed back and the one this was.
     */
    Snapshot with(Region tile, List<String> tilePalette, List<RegionRenderer.View.Run> runs) {
        Map<String, Short> index = new LinkedHashMap<>();
        List<String> merged = new ArrayList<>(palette);

        for (int entry = 0; entry < palette.size(); entry++) {
            index.put(palette.get(entry), (short) entry);
        }

        short[] remap = new short[tilePalette.size()];

        for (int entry = 0; entry < tilePalette.size(); entry++) {
            String block = tilePalette.get(entry);
            Short known = index.get(block);

            if (known == null) {
                if (merged.size() >= MAX_PALETTE) {
                    throw new IllegalArgumentException("the palette would pass %d kinds of block".formatted(MAX_PALETTE));
                }
                known = (short) merged.size();
                merged.add(block);
                index.put(block, known);
            }
            remap[entry] = known;
        }

        long expected = tile.blocks();
        long spelled = 0;

        /* Judged whole before a block is laid, since the array is shared and a half-laid tile is not a state to leave it in. */
        for (RegionRenderer.View.Run run : runs) {
            if (run.block() < 0 || run.block() >= remap.length) {
                throw new IllegalArgumentException("a run names palette entry %d, and the palette has %d"
                        .formatted(run.block(), remap.length));
            }
            if (run.count() < 0) {
                throw new IllegalArgumentException("a run of %s is %d blocks long"
                        .formatted(tilePalette.get(run.block()), run.count()));
            }
            spelled += run.count();
        }
        if (spelled != expected) {
            throw new IllegalArgumentException("the runs spell out %d of the %d blocks of the tile".formatted(spelled, expected));
        }

        int at = 0;

        for (RegionRenderer.View.Run run : runs) {
            for (int drawn = 0; drawn < run.count(); drawn++) {
                int local = at++;
                int x = tile.minX() + (int) (local % tile.sizeX());
                int z = tile.minZ() + (int) ((local / tile.sizeX()) % tile.sizeZ());
                int y = tile.minY() + (int) (local / (tile.sizeX() * tile.sizeZ()));

                blocks[index(x, y, z)] = remap[run.block()];
            }
        }
        return new Snapshot(id, name, box, merged, blocks, created, origin);
    }

    /**
     * A window of the box as the renderer draws one: the palette it uses, in runs, with the blocks
     * nobody read named as such so the map has a letter for them rather than a hole.
     */
    RegionRenderer.View view(Region window) {
        return view(window, true);
    }

    RegionRenderer.View view(Region window, boolean includeAir) {
        List<String> used = new ArrayList<>();
        Map<Short, Integer> index = new LinkedHashMap<>();
        List<RegionRenderer.View.Run> runs = new ArrayList<>();
        int last = -1;
        int count = 0;

        for (int y = window.minY(); y <= window.maxY(); y++) {
            for (int z = window.minZ(); z <= window.maxZ(); z++) {
                for (int x = window.minX(); x <= window.maxX(); x++) {
                    short block = at(x, y, z);

                    if (!includeAir && block != UNREAD && RegionRenderer.isAir(palette.get(block))) {
                        continue;
                    }

                    Integer entry = index.get(block);

                    if (entry == null) {
                        entry = used.size();
                        used.add(block == UNREAD ? "(unread)" : palette.get(block));
                        index.put(block, entry);
                    }
                    if (entry != last && count > 0) {
                        runs.add(new RegionRenderer.View.Run(last, count));
                        count = 0;
                    }
                    last = entry;
                    count++;
                }
            }
        }
        if (count > 0) {
            runs.add(new RegionRenderer.View.Run(last, count));
        }

        return new RegionRenderer.View(
                new Point(window.minX(), window.minY(), window.minZ()),
                new Point(window.maxX(), window.maxY(), window.maxZ()),
                new RegionRenderer.View.Size((int) window.sizeX(), (int) window.sizeY(), (int) window.sizeZ()),
                (int) window.blocks(), used, runs, 0, 0);
    }

    /** How many of each palette entry there are, in palette order; unread blocks count for nothing. */
    long[] counts() {
        long[] counts = new long[palette.size()];

        for (short block : blocks) {
            if (block != UNREAD) {
                counts[block]++;
            }
        }
        return counts;
    }

    short[] raw() {
        return blocks;
    }
}
