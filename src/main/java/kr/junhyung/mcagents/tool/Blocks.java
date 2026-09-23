package kr.junhyung.mcagents.tool;

import java.util.Set;

/**
 * Whether a block is something you walk through or something that stops you.
 *
 * <p>The distinction a room needs is not opaque against see-through. Glass is a wall: a greenhouse
 * is a room, and reading it as outdoors because you can see out of it would be measuring the light
 * rather than the building. What makes a wall a wall is that it is in the way.
 *
 * <p>So the rule is movement, and the default is shell. A name this does not know is treated as
 * something in the way, which is the safe direction: a wall it failed to recognise still stops a
 * fill at the right place, while an unknown treated as open lets a room leak into the street and
 * the answer is then wrong about everything rather than about one block.
 */
final class Blocks {

    private static final String VANILLA = "minecraft:";

    /** Blocks a room's air runs through: nothing in the way, whatever it looks like. */
    private static final Set<String> OPEN = Set.of(
            "air", "cave_air", "void_air", "light", "water", "bubble_column",
            "torch", "wall_torch", "soul_torch", "soul_wall_torch", "redstone_torch", "redstone_wall_torch",
            "lever", "tripwire", "tripwire_hook", "redstone_wire", "repeater", "comparator",
            "rail", "powered_rail", "detector_rail", "activator_rail", "ladder", "vine", "scaffolding",
            "sign", "wall_sign", "hanging_sign", "wall_hanging_sign", "item_frame", "glow_item_frame",
            "painting", "flower_pot", "lily_pad", "snow", "carpet", "moss_carpet", "pale_moss_carpet",
            "button", "pressure_plate", "grass", "short_grass", "tall_grass", "fern", "large_fern",
            "dead_bush", "seagrass", "kelp", "kelp_plant", "sugar_cane", "bamboo", "cobweb",
            "sculk_vein", "glow_lichen", "hanging_roots", "small_dripleaf", "big_dripleaf",
            "cave_vines", "cave_vines_plant", "twisting_vines", "weeping_vines", "string", "end_rod",
            "chain", "lantern", "soul_lantern", "candle", "amethyst_cluster", "spore_blossom");

    /** The words a block's name ends in that mean the same as being in {@link #OPEN}. */
    private static final Set<String> OPEN_SUFFIXES = Set.of(
            "_torch", "_wall_torch", "_button", "_pressure_plate", "_sign", "_wall_sign",
            "_hanging_sign", "_wall_hanging_sign", "_carpet", "_candle", "_sapling", "_rail",
            "_banner", "_wall_banner", "_flower", "_tulip", "_orchid", "_bush", "_lantern",
            "_coral_fan", "_coral_wall_fan", "_amethyst_bud", "_cluster");

    private Blocks() {
    }

    /**
     * Whether a fill runs through this block.
     *
     * <p>The state is dropped first. A door is a wall open or shut -- a room with its door open is
     * still that room, and measuring it differently depending on which way the door was left is
     * measuring the door.
     */
    static boolean open(String block) {
        if (block == null) {
            return false;
        }
        String name = plain(block);

        if (OPEN.contains(name)) {
            return true;
        }
        for (String suffix : OPEN_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** A name without its namespace and without its state, which is what either set holds. */
    static String plain(String block) {
        int state = block.indexOf('[');
        String named = state < 0 ? block : block.substring(0, state);

        return named.startsWith(VANILLA) ? named.substring(VANILLA.length()) : named;
    }

    /**
     * Whether a block is one a builder put in a wall to see or walk through.
     *
     * <p>These are shell -- they stop a fill -- and they are also how a room is entered and lit,
     * so a measurement that lists them has named the openings without going looking for them.
     */
    static boolean opening(String block) {
        String name = plain(block);

        return name.contains("glass") || name.contains("bars") || name.endsWith("_door")
                || name.endsWith("_trapdoor") || name.endsWith("_gate") || name.equals("barrier");
    }
}
