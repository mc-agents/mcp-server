package kr.junhyung.mcagents.tool;

import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.junhyung.mcagents.catalog.ToolSpec;
import kr.junhyung.mcagents.render.RegionRenderer;
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

    private static final RegionRenderer RENDERER = new RegionRenderer();

    private final RegionStore store;

    public StoredRegions(RegionStore store) {
        this.store = store;
    }

    public McpSchema.CallToolResult call(ToolSpec spec, Map<String, Object> given) {
        Map<String, Object> arguments = given == null ? Map.of() : given;

        return switch (spec.name()) {
            case "list-regions" -> list();
            case "show-region" -> show(spec, arguments);
            case "import-region" -> imported(spec, arguments);
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
}
