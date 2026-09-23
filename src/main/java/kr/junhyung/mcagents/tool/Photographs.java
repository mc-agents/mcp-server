package kr.junhyung.mcagents.tool;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.junhyung.mcagents.bot.BotSession;
import kr.junhyung.mcagents.bot.FeedEntry;
import kr.junhyung.mcagents.catalog.Catalog;
import kr.junhyung.mcagents.catalog.ToolSpec;
import kr.junhyung.mcagents.protocol.Messages;
import kr.junhyung.mcagents.render.RegionRenderer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * A room as it looks, from several places at once.
 *
 * <p>A block list says what a room is built of and a map says what shape it is, and neither says
 * whether it looks like anything. The only thing that does is a picture, and a picture needs the
 * bot stood somewhere sensible -- which by hand was a teleport, a turn, a wait and a screenshot
 * per angle, four times, with the angles chosen by guesswork.
 *
 * <p>So the box is the argument and the viewpoints are worked out from it. Two framings, because
 * they answer different questions: from the top corners looking in is the room as a composition,
 * and from the middle looking out is the room as somebody standing in it sees it.
 */
@Component
public class Photographs {

    /** How many views one call takes at most, whatever the framing asks for. */
    private static final int MAX_VIEWS = 8;

    /**
     * How many pixels one answer carries.
     *
     * <p>Eight frames at the default size. Bounding the count and the size apart from each other
     * would still allow eight of the largest, which is forty times this, and an answer that big is
     * one an agent pays for twice -- once to receive and once to look at.
     */
    private static final long MAX_PIXELS = 8L * 854 * 480;

    /** How long the client is given to have the room's blocks before anything is photographed. */
    private static final int CHUNKS_MS = 15_000;

    /**
     * How long the client is given between arriving somewhere and being photographed.
     *
     * <p>A client that is idle runs at a frame a second and builds chunk meshes at that rate, so a
     * screenshot taken straight after a teleport is of a room half drawn. A call in flight holds it
     * at sixty, which is what makes this a wait of two thirds of a second rather than of forty.
     */
    private static final int SETTLE_TICKS = 40;

    /** Where a corner camera starts, as a share of the room: far enough in to see two walls. */
    private static final int INSET = 8;

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** One frame: where the eye is, what it looks at, and what to call it. */
    private record View(String name, double x, double y, double z, double atX, double atY, double atZ) {}

    private final Catalog catalog;
    private final RemoteTools remote;
    private final Commands commands;

    public Photographs(Catalog catalog, RemoteTools remote, Commands commands) {
        this.catalog = catalog;
        this.remote = remote;
        this.commands = commands;
    }

    public McpSchema.CallToolResult take(ToolSpec spec, BotSession bot, Map<String, Object> given,
            Progress progress) {
        Map<String, Object> arguments = given == null ? Map.of() : given;
        Region box = Region.of(spec.name(), arguments);
        String framing = ToolDispatcher.stringArg(arguments, "framing");
        int width = arguments.get("width") instanceof Number number ? number.intValue() : 854;
        int height = arguments.get("height") instanceof Number number ? number.intValue() : 480;
        List<View> views = views(box, framing == null ? "corners" : framing);

        if (views.size() > MAX_VIEWS) {
            views = views.subList(0, MAX_VIEWS);
        }
        if ((long) views.size() * width * height > MAX_PIXELS) {
            throw new IllegalArgumentException(
                    "%d views at %dx%d is %d pixels, and one answer carries %d. Ask for fewer views or a smaller frame."
                            .formatted(views.size(), width, height,
                                    (long) views.size() * width * height, MAX_PIXELS));
        }
        List<View> taking = views;

        return remote.exclusively(spec, bot, () -> take(bot, box, taking, width, height,
                Boolean.TRUE.equals(arguments.get("hud")), progress));
    }

    private McpSchema.CallToolResult take(BotSession bot, Region box, List<View> views, int width, int height,
            boolean hud, Progress progress) {
        ToolSpec screenshot = catalog.require("screenshot");

        ToolDispatcher.offerCheck(screenshot, bot);

        Messages.Position stood = position(bot);
        List<McpSchema.Content> content = new ArrayList<>();
        List<String> lines = new ArrayList<>();

        try {
            String late = arrived(bot, box);

            if (late != null) {
                return ToolDispatcher.failure(late);
            }
            for (int at = 0; at < views.size(); at++) {
                View view = views.get(at);

                progress.report("view %d of %d (%s)".formatted(at + 1, views.size(), view.name()),
                        at, views.size());
                lines.add("%d. %s, from %.1f, %.1f, %.1f looking at %.1f, %.1f, %.1f"
                        .formatted(at + 1, view.name(), view.x(), view.y(), view.z(),
                                view.atX(), view.atY(), view.atZ()));

                McpSchema.CallToolResult shot = shoot(bot, screenshot, view, width, height, hud);

                if (Boolean.TRUE.equals(shot.isError())) {
                    lines.set(lines.size() - 1, lines.getLast() + " -- not taken: " + ToolDispatcher.textOf(shot));
                    continue;
                }
                shot.content().stream().filter(McpSchema.ImageContent.class::isInstance).forEach(content::add);
            }
        } finally {
            restore(bot, stood);
        }
        lines.addFirst("%d view(s) of %s, %dx%d each, in the order they are numbered."
                .formatted(content.size(), box, width, height));

        content.addFirst(McpSchema.TextContent.builder(Trust.mark(String.join("\n", lines))).build());

        return McpSchema.CallToolResult.builder().content(content).isError(false).build();
    }

    private McpSchema.CallToolResult shoot(BotSession bot, ToolSpec screenshot, View view, int width,
            int height, boolean hud) {
        commands.send(bot, "tp %.2f %.2f %.2f".formatted(view.x(), view.y() - 1.62, view.z()));
        settle(bot);

        ToolSpec lookAt = catalog.require("look-at");

        if (bot.supports(lookAt.name())) {
            remote.call(lookAt, bot, Map.of("x", view.atX(), "y", view.atY(), "z", view.atZ()));
        }
        Map<String, Object> arguments = new LinkedHashMap<>();

        arguments.put("width", width);
        arguments.put("height", height);
        arguments.put("hud", hud);

        return remote.call(screenshot, bot, arguments);
    }

    /** Hold the client busy long enough to finish drawing what it was just teleported into. */
    private void settle(BotSession bot) {
        ToolSpec wait = catalog.require("wait-ticks");

        if (bot.supports(wait.name())) {
            remote.call(wait, bot, Map.of("ticks", SETTLE_TICKS));
        } else {
            Commands.sleep(SETTLE_TICKS * 50);
        }
    }

    /**
     * Where each frame is taken from.
     *
     * <p>A teleport puts the feet down and the eye sits 1.62 above them, so every point here is an
     * eye and the teleport subtracts. A corner view is inset from the box by an eighth of the room
     * so that two walls and the floor are in frame rather than the inside of a wall.
     */
    private static List<View> views(Region box, String framing) {
        List<View> views = new ArrayList<>();
        double midX = box.minX() + box.sizeX() / 2.0;
        double midY = box.minY() + box.sizeY() / 2.0;
        double midZ = box.minZ() + box.sizeZ() / 2.0;

        if (!"centre".equals(framing)) {
            int inset = (int) Math.max(1, Math.min(box.sizeX(), box.sizeZ()) / INSET);
            double high = box.maxY() - 0.5;

            for (int[] corner : new int[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}}) {
                double x = corner[0] == 0 ? box.minX() + inset + 0.5 : box.maxX() - inset + 0.5;
                double z = corner[1] == 0 ? box.minZ() + inset + 0.5 : box.maxZ() - inset + 0.5;

                views.add(new View("%s%s corner, looking in".formatted(corner[1] == 0 ? "north" : "south",
                        corner[0] == 0 ? "west" : "east"), x, high, z, midX, midY, midZ));
            }
        }
        if (!"corners".equals(framing)) {
            /* A span past the wall, so the wall itself is what fills the frame and not a block of it. */
            double reach = Math.max(box.sizeX(), box.sizeZ());
            double eye = box.minY() + 1.62;

            views.add(new View("centre, facing north", midX, eye, midZ, midX, eye, midZ - reach));
            views.add(new View("centre, facing east", midX, eye, midZ, midX + reach, eye, midZ));
            views.add(new View("centre, facing south", midX, eye, midZ, midX, eye, midZ + reach));
            views.add(new View("centre, facing west", midX, eye, midZ, midX - reach, eye, midZ));
        }
        return views;
    }

    /**
     * Put the bot in the room and wait for the client to have it.
     *
     * <p>A screenshot of chunks that have not arrived is of an empty sky, which is what two frames
     * taken over this town came back as. The blocks are what prove they arrived; the pictures
     * cannot say so themselves.
     */
    private String arrived(BotSession bot, Region box) {
        ToolSpec readRegion = catalog.require("read-region");

        ToolDispatcher.offerCheck(readRegion, bot);

        long mark = bot.feed("chat").nextSeq();

        commands.send(bot, "tp %d %d %d".formatted(box.minX() + (int) (box.sizeX() / 2), box.maxY(),
                box.minZ() + (int) (box.sizeZ() / 2)));

        FeedEntry no = Commands.refused(Commands.systemLines(bot, mark));

        if (no != null) {
            return Trust.mark(
                    "the bot could not be teleported into %s, and a room is photographed from inside it: /tp came back as \"%s\"."
                            .formatted(box, no.rendered()));
        }
        /* One layer of the floor is enough to say the chunks under the room are here. */
        Region floor = new Region(box.minX(), box.minY(), box.minZ(), box.maxX(), box.minY(), box.maxZ());
        Map<String, Object> arguments = new LinkedHashMap<>(floor.corners());

        arguments.put("includeAir", true);

        long deadline = System.currentTimeMillis() + CHUNKS_MS;

        while (true) {
            Messages.Result result = remote.fetch(readRegion, bot, arguments).result();

            if (result.ok() && result.data() != null
                    && MAPPER.convertValue(result.data(), RegionRenderer.View.class).missing() == 0) {
                return null;
            }
            if (System.currentTimeMillis() >= deadline) {
                return "the chunks of %s never arrived at the client in the %ds after the bot was put in it, and a photograph of those is of an empty sky."
                        .formatted(box, CHUNKS_MS / 1_000);
            }
            Commands.sleep(Commands.POLL_MS);
        }
    }

    private Messages.Position position(BotSession bot) {
        Messages.Status status = bot.status();

        return status == null ? null : status.position();
    }

    private void restore(BotSession bot, Messages.Position stood) {
        if (stood != null) {
            commands.send(bot, "tp %.2f %.2f %.2f".formatted(stood.x(), stood.y(), stood.z()));
        }
    }
}
