package kr.junhyung.mcagents.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import kr.junhyung.mcagents.render.Text;

/**
 * A box of blocks, as the two corners a caller gave describe it.
 *
 * <p>Held by the server because a JSON Schema can bound a coordinate but not the product of six of
 * them. A corner mistyped by a thousand is a schema-valid box of a billion blocks, and the whole
 * value of refusing it is refusing it here rather than after the bot has started walking one.
 *
 * <p>The corners come in either order and are settled into a lower and an upper one, which is the
 * same normalisation the bot reports back, so an answer names the box the same way whichever corner
 * was given first.
 */
record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    /** The most blocks one call takes, and the most it may span on any one axis. */
    static final int MAX_BLOCKS = 32_768;

    static final int MAX_SPAN = 64;

    static Region of(String tool, Map<String, Object> arguments) {
        int[] from = corner(tool, arguments, "from");
        int[] to = corner(tool, arguments, "to");
        Region box = new Region(
                Math.min(from[0], to[0]), Math.min(from[1], to[1]), Math.min(from[2], to[2]),
                Math.max(from[0], to[0]), Math.max(from[1], to[1]), Math.max(from[2], to[2]));

        if (box.sizeX() > MAX_SPAN || box.sizeY() > MAX_SPAN || box.sizeZ() > MAX_SPAN) {
            throw new IllegalArgumentException("\"%s\" spans %s, and no axis may be more than %d blocks"
                    .formatted(tool, box.extent(), MAX_SPAN));
        }
        if (box.blocks() > MAX_BLOCKS) {
            throw new IllegalArgumentException("\"%s\" holds %d blocks, and the most it takes is %d"
                    .formatted(tool, box.blocks(), MAX_BLOCKS));
        }
        return box;
    }

    /**
     * The call with its corners as {@link #of} read them, which is what then has to be sent.
     *
     * <p>{@code of} takes each coordinate through {@code intValue()} and settles the two corners
     * into a lower and an upper one; the maps the caller passed have had neither done to them, and
     * {@link Normaliser} copies a nested object across without looking inside it. That left the box
     * being measured and the box being sent as two different objects, which a caller could give two
     * different shapes: 2^32 + 10 is a whole number, measures here as 10, and crosses the wire as
     * itself.
     */
    static Map<String, Object> settle(String tool, Map<String, Object> arguments) {
        Region box = of(tool, arguments);
        Map<String, Object> settled = new LinkedHashMap<>(arguments);

        settled.put("from", point(box.minX, box.minY, box.minZ));
        settled.put("to", point(box.maxX, box.maxY, box.maxZ));

        return settled;
    }

    private static Map<String, Object> point(int x, int y, int z) {
        Map<String, Object> corner = new LinkedHashMap<>();

        corner.put("x", x);
        corner.put("y", y);
        corner.put("z", z);

        return corner;
    }

    /* Widened, because a box from Integer.MIN_VALUE to Integer.MAX_VALUE spans zero blocks in int. */
    long sizeX() {
        return (long) maxX - minX + 1;
    }

    long sizeY() {
        return (long) maxY - minY + 1;
    }

    long sizeZ() {
        return (long) maxZ - minZ + 1;
    }

    long blocks() {
        return sizeX() * sizeY() * sizeZ();
    }

    /** What WorldEdit's selection commands take, which is the corner with no spaces in it. */
    String lowerCorner() {
        return "%d,%d,%d".formatted(minX, minY, minZ);
    }

    String upperCorner() {
        return "%d,%d,%d".formatted(maxX, maxY, maxZ);
    }

    String extent() {
        return "%d x %d x %d".formatted(sizeX(), sizeY(), sizeZ());
    }

    @Override
    public String toString() {
        return "%s to %s, %s, %d blocks".formatted(Text.block(minX, minY, minZ), Text.block(maxX, maxY, maxZ),
                extent(), blocks());
    }

    private static int[] corner(String tool, Map<String, Object> arguments, String name) {
        Object given = arguments == null ? null : arguments.get(name);

        if (!(given instanceof Map<?, ?> corner)) {
            throw new IllegalArgumentException(
                    "\"%s\" needs \"%s\" as an object with x, y and z".formatted(tool, name));
        }
        return new int[] {axis(tool, name, corner, "x"), axis(tool, name, corner, "y"), axis(tool, name, corner, "z")};
    }

    /**
     * A fraction is refused rather than floored. Which block a coordinate falls in is the bot's
     * knowledge, and truncating here would move a corner at z=-12.5 to block -12 -- the one next
     * door, silently, and only on the negative side of the axis.
     */
    private static int axis(String tool, String corner, Map<?, ?> given, String axis) {
        Object value = given.get(axis);

        if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
            throw new IllegalArgumentException("\"%s\" %s.%s is %s, and a block coordinate is a whole number"
                    .formatted(tool, corner, axis, value));
        }
        return number.intValue();
    }
}
