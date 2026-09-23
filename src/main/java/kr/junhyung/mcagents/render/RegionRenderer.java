package kr.junhyung.mcagents.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A box of blocks as something an agent can see.
 *
 * <p>The counts say what a build is made of. They do not say what it looks like, and telling a wall
 * from a floor from an arch is most of why a region is read back at all -- so a box small enough to
 * fit on a screen is drawn as well: one character a block from a legend, a layer at a time, y
 * ascending, x to the right and z downwards, which is how a map is read.
 *
 * <p>The drawing is only possible while the runs tile the box exactly. A block the bot skipped --
 * air dropped by {@code includeAir}, a chunk the client has not got, a y past the world -- leaves
 * no gap in the run stream to put it back into, so every block after it would be drawn one place
 * early. When that has happened the counts are still right and the map is left out, with the reason
 * said rather than a picture that is quietly shifted.
 */
public final class RegionRenderer implements Renderer<RegionRenderer.View> {

    /**
     * How big a box is still worth drawing.
     *
     * <p>A layer is at most 64 characters wide -- read-region's own limit settles that -- so what
     * this decides is how many lines. 4096 blocks is about five kilobytes of text, a picture an
     * agent takes in at once. The whole 32768 a call may ask for is eight times that, and at that
     * size the counts are the answer and the picture is what buries them.
     */
    public static final int DRAWABLE_BLOCKS = 4_096;

    /** One character a palette entry, in palette order. */
    private static final String LEGEND = "abcdefghijklmnopqrstuvwxyz0123456789";

    /**
     * Air keeps the dot whatever its place in the palette, because empty space should read as empty.
     *
     * <p>Without the namespace, which is how the palette carries a vanilla block. A bot that sent it
     * anyway would still be drawing air, so the prefix is taken off before the comparison rather
     * than left to turn a picture's background into letters.
     */
    private static final Set<String> AIR = Set.of("air", "cave_air", "void_air");

    private static final String VANILLA = "minecraft:";

    private static final char AIR_MARK = '.';

    public record View(Point from, Point to, Size size, int blocks, List<String> palette, List<Run> runs,
            int missing, int outside) {

        public record Size(int x, int y, int z) {}

        public record Run(int block, int count) {}
    }

    @Override
    public String render(View view) {
        int[] counts = counts(view);
        int counted = 0;

        for (int count : counts) {
            counted += count;
        }

        boolean states = letters(view.palette(), false) <= LEGEND.length();
        boolean collapsed = !states && letters(view.palette(), true) <= LEGEND.length();
        boolean drawable = counted == view.blocks() && view.blocks() <= DRAWABLE_BLOCKS && (states || collapsed);
        char[] legend = legend(view.palette(), collapsed);
        List<String> out = new ArrayList<>();

        out.add("%s to %s, %d x %d x %d, %d blocks.".formatted(view.from(), view.to(),
                view.size().x(), view.size().y(), view.size().z(), view.blocks()));
        out.addAll(view.palette().isEmpty() ? List.of(nothing(view)) : palette(view, counts, drawable ? legend : null));

        if (view.missing() > 0) {
            out.add("%d of them are in chunks this client has not got, so nothing above counts them. Fly the bot nearer and read the box again."
                    .formatted(view.missing()));
        }
        if (view.outside() > 0) {
            out.add("%d of them are past the top or bottom of the world, where there is nothing to read."
                    .formatted(view.outside()));
        }
        if (drawable) {
            out.add(collapsed
                    ? "One character a kind of block, a layer at a time, y ascending, x to the right and z downwards. The %d states above share %d letters, because a letter each is more than the legend has:"
                            .formatted(letters(view.palette(), false), letters(view.palette(), true))
                    : "One character a block, a layer at a time, y ascending, x to the right and z downwards:");
            out.addAll(map(view, legend));
        } else if (!view.palette().isEmpty()) {
            out.add(noMap(view, counted));
        }
        return String.join("\n", out);
    }

    /**
     * How many of each palette entry the runs spell out.
     *
     * <p>An index the palette does not have is the one thing here that cannot be rendered around: a
     * run naming entry 4 of a palette of three describes a region nobody can reconstruct, and
     * saying so names the disagreement instead of drawing a plausible picture of the wrong blocks.
     */
    private static int[] counts(View view) {
        int[] counts = new int[view.palette().size()];

        for (View.Run run : view.runs()) {
            if (run.block() < 0 || run.block() >= counts.length) {
                throw new IllegalArgumentException("a run names palette entry %d, and the palette has %d"
                        .formatted(run.block(), counts.length));
            }
            /* A negative run would total up to the box while overrunning it, and the map would be drawn off its end. */
            if (run.count() < 0) {
                throw new IllegalArgumentException("a run of %s is %d blocks long"
                        .formatted(view.palette().get(run.block()), run.count()));
            }
            counts[run.block()] += run.count();
        }
        return counts;
    }

    private static char[] legend(List<String> palette, boolean collapsed) {
        Map<String, Character> taken = new LinkedHashMap<>();
        char[] marks = new char[palette.size()];
        int letter = 0;

        for (int entry = 0; entry < palette.size(); entry++) {
            String block = palette.get(entry);

            if (isAir(block)) {
                marks[entry] = AIR_MARK;
                continue;
            }
            Character shared = taken.get(mark(block, collapsed));

            if (shared != null) {
                marks[entry] = shared;
                continue;
            }
            char given = LEGEND.charAt(Math.min(letter++, LEGEND.length() - 1));

            taken.put(mark(block, collapsed), given);
            marks[entry] = given;
        }
        return marks;
    }

    /**
     * What a letter is spent on: a state of its own, or the block whatever state it is in.
     *
     * <p>Collapsing is what lets a room be drawn at all. A built room is stairs facing four ways,
     * walls in eight connection states and slabs top and bottom, and counting those separately puts
     * a plain interior past thirty-six before it has thirty-six materials in it -- the map was
     * refused for a room of eleven kinds of block wearing sixty-eight states. The counts above the
     * map still name every state, so nothing is lost; what the picture then shows is the shape,
     * which is what it was drawn for.
     */
    private static String mark(String block, boolean collapsed) {
        int state = block.indexOf('[');

        return collapsed && state > 0 ? block.substring(0, state) : block;
    }

    /**
     * How many of the legend's letters a palette spends, which is what decides whether it fits.
     *
     * <p>Air spends none of them, since it keeps its dot. Measuring the palette itself refused a
     * map to a palette of thirty-seven with air among them, which spends thirty-six and fits.
     */
    private static int letters(List<String> palette, boolean collapsed) {
        return (int) palette.stream().filter(block -> !isAir(block))
                .map(block -> mark(block, collapsed)).distinct().count();
    }

    public static boolean isAir(String block) {
        return AIR.contains(block.startsWith(VANILLA) ? block.substring(VANILLA.length()) : block);
    }

    /**
     * What the box is made of, most of it first, because "what is this built from" is the question
     * a count answers. When a map follows, the character each block is drawn as leads its line, so
     * the listing is the legend too rather than a second copy of it to keep in step.
     */
    private static List<String> palette(View view, int[] counts, char[] legend) {
        List<Integer> order = new ArrayList<>();

        for (int entry = 0; entry < view.palette().size(); entry++) {
            order.add(entry);
        }
        order.sort((a, b) -> counts[b] - counts[a]);

        int widest = 0;
        for (int count : counts) {
            widest = Math.max(widest, Integer.toString(count).length());
        }

        String format = "  %s%" + widest + "d %s (%d%%)";
        List<String> lines = new ArrayList<>();

        for (int entry : order) {
            lines.add(format.formatted(legend == null ? "" : legend[entry] + "  ", counts[entry],
                    view.palette().get(entry), Text.percent(counts[entry] / (double) view.blocks())));
        }
        return lines;
    }

    /** The map, a layer a line-group, in the order the runs were walked. */
    private static List<String> map(View view, char[] legend) {
        char[] blocks = new char[view.blocks()];
        int at = 0;

        for (View.Run run : view.runs()) {
            for (int drawn = 0; drawn < run.count(); drawn++) {
                blocks[at++] = legend[run.block()];
            }
        }

        List<String> lines = new ArrayList<>();
        int width = view.size().x();
        int depth = view.size().z();

        for (int layer = 0; layer < view.size().y(); layer++) {
            lines.add("  y=" + (view.from().y() + layer));

            for (int row = 0; row < depth; row++) {
                int start = (layer * depth + row) * width;
                lines.add("    " + new String(blocks, start, width));
            }
        }
        return lines;
    }

    /**
     * An empty palette, which is either nothing read or nothing kept.
     *
     * <p>Nothing kept is what a box of nothing but air reads as with {@code includeAir} false, and
     * that is as far as it can be put: the DTO carries no {@code includeAir}, so stating it as the
     * reason was the renderer asserting an argument it was never sent.
     */
    private static String nothing(View view) {
        return view.missing() + view.outside() >= view.blocks()
                ? "None of it was read."
                : "Nothing in it was counted, which is what a box holding nothing but air reads as when includeAir is false.";
    }

    private static String noMap(View view, int counted) {
        if (counted != view.blocks()) {
            return "No map: the runs spell out %d of the %d blocks, so where each one sits cannot be worked out. %s"
                    .formatted(counted, view.blocks(), remedy(view));
        }
        if (view.blocks() > DRAWABLE_BLOCKS) {
            return "No map: %d blocks is more than the %d one is drawn for. Read it in boxes of that size or smaller."
                    .formatted(view.blocks(), DRAWABLE_BLOCKS);
        }
        return "No map: %d kinds of block need a letter each even with their states put together, and the legend has %d. Read it in smaller boxes."
                .formatted(letters(view.palette(), true), LEGEND.length());
    }

    /** What to do about a box the runs do not tile, which is a different thing for each reason. */
    private static String remedy(View view) {
        if (view.missing() > 0) {
            return "Read a box this client holds whole.";
        }
        return view.outside() > 0 ? "Read a box that is inside the world." : "Read it again with includeAir true.";
    }
}
