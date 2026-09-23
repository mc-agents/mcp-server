package kr.junhyung.mcagents.tool;

import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import kr.junhyung.mcagents.catalog.ToolSpec;
import kr.junhyung.mcagents.render.RegionRenderer;
import kr.junhyung.mcagents.render.Text;
import org.springframework.stereotype.Component;

/**
 * The tools that work on a kept region without a bot: listing them, drawing a window of one, and
 * making one out of runs an agent wrote itself.
 *
 * <p>A region an agent designs is the same thing as one a bot read -- a box, a palette and the runs
 * that spell it out -- so it comes in through the same encoding read-region answers with, and
 * once kept it is put down the same way. That is what lets an agent build a shape it worked out
 * rather than one WorldEdit has a command for.
 */
@Component
public class StoredRegions {

    /** What show-region draws at most, which is what the renderer draws a map for. */
    static final int MAX_WINDOW = RegionRenderer.DRAWABLE_BLOCKS;

    /** The most blocks a region written by hand may hold: runs are typed, and this is a lot of typing. */
    static final long MAX_IMPORTED = Region.MAX_BLOCKS;

    /** An imported box may be as wide as a surveyed one. */
    private static final int MAX_SPAN = RegionSurvey.MAX_SPAN;

    /** Under how many blocks a room is worth remarking on as a small one. */
    private static final int CRAMPED = 24;

    /** How many kinds of shell block are listed before the tail is left off. */
    private static final int SHELL_KINDS = 12;

    private static final RegionRenderer RENDERER = new RegionRenderer();

    private final RegionStore store;
    private final CustomBlocks customBlocks;

    public StoredRegions(RegionStore store, CustomBlocks customBlocks) {
        this.store = store;
        this.customBlocks = customBlocks;
    }

    public McpSchema.CallToolResult call(ToolSpec spec, Map<String, Object> given) {
        Map<String, Object> arguments = given == null ? Map.of() : given;

        return switch (spec.name()) {
            case "list-regions" -> list();
            case "show-region" -> show(spec, arguments);
            case "import-region" -> imported(spec, arguments);
            case "measure-room" -> measure(spec, arguments);
            default -> ToolDispatcher.failure("%s is not a region tool".formatted(spec.name()));
        };
    }

    private McpSchema.CallToolResult list() {
        List<Snapshot> all = store.all();

        if (all.isEmpty()) {
            return ToolDispatcher.text(
                    "No regions are kept. read-region keeps what it reads, import-region keeps runs you give it, and POST /regions on this server keeps a schematic file.");
        }

        List<String> lines = new ArrayList<>();
        Instant now = Instant.now();

        lines.add("%d region(s) kept, %d blocks of the %d this server holds at once, oldest first:"
                .formatted(all.size(), store.held(), RegionStore.MAX_BLOCKS));
        for (Snapshot region : all) {
            long unread = region.unread();

            lines.add("  %s%s: %s, %d kinds of block%s, from %s, %s ago".formatted(
                    region.id(),
                    region.name() == null ? "" : " (\"" + region.name() + "\")",
                    region.box(),
                    region.palette().size(),
                    unread == 0 ? "" : ", %d blocks unread".formatted(unread),
                    region.origin(),
                    age(Duration.between(region.created(), now))));
        }
        return ToolDispatcher.text(String.join("\n", lines));
    }

    /**
     * A window of a kept region, drawn.
     *
     * <p>The window is in world coordinates, the region's own, and has to be inside it: a window
     * that reaches outside would be drawn from blocks the region does not have, and the renderer's
     * way of saying so is a map with holes in it. The size limit is the renderer's, since a window
     * is only worth asking for when it is drawn.
     */
    private McpSchema.CallToolResult show(ToolSpec spec, Map<String, Object> arguments) {
        Snapshot region = store.require(required(arguments, "region"));
        Region window = arguments.containsKey("from") || arguments.containsKey("to")
                ? Region.corners(spec.name(), arguments)
                : region.box();

        if (window.clip(region.box()) == null || !window.equals(window.clip(region.box()))) {
            throw new IllegalArgumentException("the window %s reaches outside region %s, which is %s"
                    .formatted(window, region.id(), region.box()));
        }
        if (window.blocks() > MAX_WINDOW) {
            throw new IllegalArgumentException(
                    "the window holds %d blocks, and show-region draws at most %d a call: ask for a layer or two at a time, as from y to y."
                            .formatted(window.blocks(), MAX_WINDOW));
        }

        return ToolDispatcher.text("Region %s%s, window: %s".formatted(region.id(),
                region.name() == null ? "" : " (\"" + region.name() + "\")", RENDERER.render(region.view(window))));
    }

    /**
     * Runs an agent wrote, kept as a region at the corner it names.
     *
     * <p>The encoding is read-region's own, and its rule is the same: the runs walk y, then z, then
     * x, and have to spell out the box exactly. Anything short or long is refused with the count,
     * since a run stream that does not tile the box has no positions in it.
     */
    private McpSchema.CallToolResult imported(ToolSpec spec, Map<String, Object> arguments) {
        Region box = Region.corners(spec.name(), Map.of("from", cornerOf(arguments, "at"), "to", cornerOf(arguments, "at")));
        Map<String, Object> size = objectOf(arguments, "size");
        int sizeX = axisOf(size, "x");
        int sizeY = axisOf(size, "y");
        int sizeZ = axisOf(size, "z");

        box = new Region(box.minX(), box.minY(), box.minZ(), box.minX() + sizeX - 1, box.minY() + sizeY - 1, box.minZ() + sizeZ - 1);
        box.bounded(spec.name(), MAX_SPAN, MAX_IMPORTED);

        List<String> palette = new ArrayList<>();

        if (!(arguments.get("palette") instanceof List<?> given) || given.isEmpty()) {
            throw new IllegalArgumentException("\"%s\" needs \"palette\" as a list of block ids".formatted(spec.name()));
        }
        for (Object block : given) {
            if (!(block instanceof String id) || id.isBlank() || id.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("a palette entry is \"%s\", and a block id has no spaces in it".formatted(block));
            }
            palette.add(id);
        }

        List<RegionRenderer.View.Run> runs = new ArrayList<>();

        if (!(arguments.get("runs") instanceof List<?> spelled) || spelled.isEmpty()) {
            throw new IllegalArgumentException("\"%s\" needs \"runs\" as a list of {block, count}".formatted(spec.name()));
        }
        for (Object run : spelled) {
            if (!(run instanceof Map<?, ?> pair) || !(pair.get("block") instanceof Number block)
                    || !(pair.get("count") instanceof Number count)) {
                throw new IllegalArgumentException("a run is %s, and a run is {block, count}".formatted(run));
            }
            runs.add(new RegionRenderer.View.Run(block.intValue(), count.intValue()));
        }

        Snapshot kept = Snapshot.blank(store.fresh(), ToolDispatcher.stringArg(arguments, "name"), box, Instant.now(), "import-region")
                .with(box, palette, runs);
        List<String> evicted = store.keep(kept);

        return ToolDispatcher.text(RENDERER.render(kept.view(box)) + "\n" + RegionSurvey.kept(kept, evicted, null));
    }

    private static Map<String, Object> cornerOf(Map<String, Object> arguments, String name) {
        return objectOf(arguments, name);
    }

    private static Map<String, Object> objectOf(Map<String, Object> arguments, String name) {
        if (arguments.get(name) instanceof Map<?, ?> given) {
            @SuppressWarnings("unchecked")
            Map<String, Object> object = (Map<String, Object>) given;
            return object;
        }
        throw new IllegalArgumentException("\"%s\" is needed as an object with x, y and z".formatted(name));
    }

    private static int axisOf(Map<String, Object> size, String axis) {
        if (size.get(axis) instanceof Number number && number.intValue() >= 1
                && number.doubleValue() == number.intValue()) {
            return number.intValue();
        }
        throw new IllegalArgumentException("size.%s is %s, and a size is a whole number of at least 1".formatted(axis, size.get(axis)));
    }

    private static String required(Map<String, Object> arguments, String name) {
        String value = ToolDispatcher.stringArg(arguments, name);

        if (value == null) {
            throw new IllegalArgumentException("\"%s\" is required".formatted(name));
        }
        return value;
    }

    private static String age(Duration since) {
        if (since.toHours() > 0) {
            return since.toHours() + "h";
        }
        return since.toMinutes() > 0 ? since.toMinutes() + "m" : since.toSeconds() + "s";
    }

    /**
     * measure-room: how big a room is, what it is finished in, and where the way out is.
     *
     * <p>Needs no bot, because the snapshot read-region already kept holds everything this asks.
     * That matters more than it sounds: the measurement takes a point and two thresholds, and when
     * the first answer says the walls did not stop it, the agent tries again -- which with a bot in
     * the loop would be a teleport and a wait for chunks each time.
     */
    private McpSchema.CallToolResult measure(ToolSpec spec, Map<String, Object> arguments) {
        Snapshot snapshot = store.require(required(arguments, "region"));
        Region box = arguments.get("from") == null || arguments.get("to") == null
                ? snapshot.box()
                : Region.corners(spec.name(), arguments).clip(snapshot.box());

        if (box == null) {
            throw new IllegalArgumentException("the corners given are outside %s, which holds %s"
                    .formatted(snapshot.id(), snapshot.box()));
        }
        if (box.blocks() > Rooms.MAX_BLOCKS) {
            throw new IllegalArgumentException(
                    "%s holds %d blocks and a room is measured in at most %d. Give \"from\" and \"to\" to narrow it."
                            .formatted(snapshot.id(), box.blocks(), Rooms.MAX_BLOCKS));
        }
        int[] at = point(arguments);
        double enclosure = arguments.get("enclosure") instanceof Number number ? number.doubleValue() : 0.75;
        int clearance = arguments.get("clearance") instanceof Number number ? number.intValue() : 2;

        if (!box.contains(at[0], at[1], at[2])) {
            return ToolDispatcher.failure("%s is not inside %s, which is what %s holds."
                    .formatted(Text.block(at[0], at[1], at[2]), box, snapshot.id()));
        }
        Rooms.Room room = Rooms.of(snapshot, box, at, enclosure, clearance);

        if (room == null) {
            return ToolDispatcher.failure(
                    "nothing at or within 3 blocks of %s is a block a room's air runs through: %s is there. Aim at the space in a room rather than at what encloses it."
                            .formatted(Text.block(at[0], at[1], at[2]),
                                    snapshot.blockAt(at[0], at[1], at[2])));
        }
        return ToolDispatcher.text(described(snapshot, box, room, enclosure, clearance));
    }

    private String described(Snapshot snapshot, Region box, Rooms.Room room, double enclosure, int clearance) {
        List<String> out = new ArrayList<>();
        Map<String, long[]> shell = Rooms.shell(snapshot, room.cells(), box);
        long floor = room.cells().stream().mapToLong(cell -> ((long) cell[0] << 32) | cell[2]).distinct().count();

        out.add("The room around %s in %s: %d block(s) of space over %d of floor, within %s."
                .formatted(Text.block(room.seed()[0], room.seed()[1], room.seed()[2]), snapshot.id(),
                        room.cells().size(), floor, room.box()));

        if (room.movedSeed()) {
            out.add("The point given was inside a block, so the nearest open one was measured from instead.");
        }
        out.add("It is %.1f blocks from side to side, %.1f front to back, and %.1f from floor to ceiling."
                .formatted((double) room.box().sizeX(), (double) room.box().sizeZ(), (double) room.box().sizeY()));

        List<String> faces = new ArrayList<>();
        CustomBlocks.Dictionary dictionary = named(snapshot);

        shell.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> entry) ->
                        entry.getValue()[0] + entry.getValue()[1] + entry.getValue()[2]).reversed())
                .limit(SHELL_KINDS)
                .forEach(entry -> faces.add("  %5d %s (%s)".formatted(
                        entry.getValue()[0] + entry.getValue()[1] + entry.getValue()[2],
                        named(dictionary, entry.getKey()), where(entry.getValue()))));

        if (!faces.isEmpty()) {
            out.add("What it is finished in, counting only the faces that look into it:");
            out.addAll(faces);
        }
        List<Map.Entry<String, Long>> openings = Rooms.openings(shell);

        if (!openings.isEmpty()) {
            out.add("Ways in and out, and what lets light in: " + openings.stream()
                    .map(entry -> "%s x%d".formatted(entry.getKey(), entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(", ")));
        }
        if (!room.frontier().isEmpty()) {
            out.add("%d block(s) of it are a doorway or a gap: entered, and too tight or too open to carry the room further. Those are where it ends."
                    .formatted(room.frontier().size()));
        }
        String leaking = leaking(room, box);

        if (leaking != null) {
            out.add(leaking);
        } else if (room.cells().size() < CRAMPED) {
            out.add("That is a small space for a room. If it should be bigger, the walls it found are furniture or a counter rather than walls, and a lower \"clearance\" gets past them -- or aim at a point with more room around it.");
        }
        if (room.unread() > 0) {
            out.add("%d block(s) of the box were never read, and a gap in the blocks reads as a way out."
                    .formatted(room.unread()));
        }
        out.add("As arguments: show-region %s from %d,%d,%d to %d,%d,%d draws it, and read-furniture over the same box says what stands in it -- the furniture is entities and no snapshot holds it."
                .formatted(snapshot.id(), room.box().minX(), room.box().minY(), room.box().minZ(),
                        room.box().maxX(), room.box().maxY(), room.box().maxZ()));

        return String.join("\n", out);
    }

    /**
     * Whether the walls stopped it, said plainly.
     *
     * <p>A fill that ran to the sides of the box did not find a room; it found however much of the
     * world was read. Saying which thresholds were used along with it is what makes the next call
     * an adjustment rather than a guess.
     */
    private static String leaking(Rooms.Room room, Region box) {
        int sides = 0;

        for (int touching : room.touches()) {
            if (touching > 0) {
                sides++;
            }
        }
        if (sides < 3) {
            return null;
        }
        return "It ran to %d sides of %s, so the walls did not stop it and this is the measurement of what was read rather than of a room. Raise \"enclosure\" above %.2f or \"clearance\" above %d, or aim at a point further inside."
                .formatted(sides, box, room.clearanceUsed(), (int) room.clearanceUsed());
    }

    /**
     * The dictionary the region's own server learned, so a wall can be named by what it is.
     *
     * <p>CraftEngine dresses a custom block as a vanilla state nobody else uses, so a room finished
     * in it reads as note blocks in three instruments -- which answers nothing about what it is
     * finished in, and that is the whole question this tool is asked. A server nobody has run
     * learn-custom-blocks against has no dictionary, and then the states are what there is.
     */
    private CustomBlocks.Dictionary named(Snapshot snapshot) {
        CustomBlocks.Dictionary dictionary = customBlocks.of(snapshot.origin());

        return dictionary == null ? customBlocks.theOnlyOne() : dictionary;
    }

    private static String named(CustomBlocks.Dictionary dictionary, String block) {
        if (dictionary == null) {
            return block;
        }
        CustomBlocks.Entry custom = dictionary.byAppearance(block);

        return custom == null ? block : custom.id();
    }

    /** Which faces of the room a block makes up, which is how a floor is told from a ceiling. */
    private static String where(long[] faces) {
        List<String> named = new ArrayList<>();

        if (faces[0] > 0) {
            named.add("%d under".formatted(faces[0]));
        }
        if (faces[1] > 0) {
            named.add("%d over".formatted(faces[1]));
        }
        if (faces[2] > 0) {
            named.add("%d around".formatted(faces[2]));
        }
        return String.join(", ", named);
    }

    private static int[] point(Map<String, Object> arguments) {
        if (!(arguments.get("at") instanceof Map<?, ?> at)) {
            throw new IllegalArgumentException("\"at\" is required: a point inside the room to measure");
        }
        return new int[] {axis(at, "x"), axis(at, "y"), axis(at, "z")};
    }

    private static int axis(Map<?, ?> at, String axis) {
        if (at.get(axis) instanceof Number value) {
            return value.intValue();
        }
        throw new IllegalArgumentException("\"at.%s\" is a whole number, and it was not given".formatted(axis));
    }
}
