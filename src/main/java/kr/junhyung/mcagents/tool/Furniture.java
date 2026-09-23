package kr.junhyung.mcagents.tool;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.junhyung.mcagents.bot.BotSession;
import kr.junhyung.mcagents.bot.FeedEntry;
import kr.junhyung.mcagents.catalog.Catalog;
import kr.junhyung.mcagents.catalog.ToolSpec;
import kr.junhyung.mcagents.protocol.Messages;
import kr.junhyung.mcagents.render.FoundEntitiesRenderer;
import kr.junhyung.mcagents.render.Text;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * What furniture a room is dressed with, read off the pieces themselves.
 *
 * <p>CraftEngine builds a piece of furniture out of entities a client can see but cannot ask
 * anything of: an item display holding an item whose model is the only clue to what it is, and an
 * interaction box beside it. Where the piece actually sits, which way it faces and which of its
 * variants it was placed as live on the server, in the plugin's own object, and nothing about them
 * reaches the client. So a bot reading a room sees a dozen anonymous displays and can copy none of
 * it.
 *
 * <p>The plugin does have one thing that says all of it: its debug stick. A left click on a piece
 * reports one property in the action bar and changes nothing -- CraftEngine cancels the hit and
 * only advances which property the stick is showing -- while a right click is the one that moves
 * and turns things. So this hits, fifteen times, which is once around the stick's cycle, and reads
 * the answers off the action bar. Nothing in the room is touched.
 *
 * <p>The cost is a second a hit. A piece is twenty seconds and a room is a few minutes, which is
 * why the box is a room and the count is bounded: this is the tool for reading a reference, not
 * for surveying a district.
 */
@Component
public class Furniture {

    /**
     * How many times one piece is hit.
     *
     * <p>The stick's cycle is fifteen states -- the variant, x and y and z at two step sizes each,
     * and seven rotation steps -- and it is carried on the item, so where in the cycle it starts is
     * wherever the last piece left it. A full turn is what guarantees every property is seen once
     * whatever that was.
     */
    private static final int HITS = 15;

    /** What CraftEngine writes on the action bar: the property the stick is on, and its value now. */
    private static final Pattern SELECTED = Pattern.compile("selected \"([a-z0-9_]+)\" \\(([^)]*)\\)");

    /** How far above a piece the bot stands to hit it: inside reach, and out of whatever it is sitting on. */
    private static final int STAND_ABOVE = 2;

    /** How long the client is given to have the box's entities after the bot is put in it. */
    private static final int CHUNKS_MS = 15_000;

    /** What the tool asks the bot for in one sweep; the box does the narrowing. */
    private static final int SWEEP = 500;

    /** How many pieces are read unless the caller says otherwise. */
    private static final int DEFAULT_COUNT = 24;

    private static final String DEBUG_STICK = "debug_stick";

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** One piece as the stick described it, with what the displays beside it say it is. */
    private record Piece(double x, double y, double z, Double rotation, String variant, List<String> models,
            int hitboxes) {

        boolean known() {
            return rotation != null || variant != null;
        }
    }

    private final Catalog catalog;
    private final RemoteTools remote;
    private final Commands commands;

    public Furniture(Catalog catalog, RemoteTools remote, Commands commands) {
        this.catalog = catalog;
        this.remote = remote;
        this.commands = commands;
    }

    public McpSchema.CallToolResult read(ToolSpec spec, BotSession bot, Map<String, Object> given, Progress progress) {
        Map<String, Object> arguments = given == null ? Map.of() : given;
        Region box = Region.of(spec.name(), arguments);
        int count = arguments.get("count") instanceof Number number ? number.intValue() : DEFAULT_COUNT;

        return remote.exclusively(spec, bot, () -> read(bot, box, count, progress));
    }

    private McpSchema.CallToolResult read(BotSession bot, Region box, int count, Progress progress) {
        Messages.Position stood = position(bot);
        List<String> notes = new ArrayList<>();

        try {
            centre(bot, box);

            List<FoundEntitiesRenderer.Entity> hitboxes = within(bot, box, "interaction");
            List<FoundEntitiesRenderer.Entity> displays = within(bot, box, "item_display");

            if (hitboxes.isEmpty()) {
                return ToolDispatcher.text(
                        "No furniture in %s: nothing in it has an interaction box, which is what CraftEngine gives a piece to be clicked on. %d item display(s) are in there, and a display with no box is a decoration placed some other way."
                                .formatted(box, displays.size()));
            }
            if (hitboxes.size() > count) {
                notes.add("%d pieces have an interaction box in there and %d were read, nearest corner first. Read the rest in a box of their own, or raise \"count\"."
                        .formatted(hitboxes.size(), count));
                hitboxes = hitboxes.subList(0, count);
            }

            String stick = armed(bot);

            if (stick != null) {
                return ToolDispatcher.failure(stick);
            }
            return ToolDispatcher.text(described(read(bot, hitboxes, displays, progress), box, notes));
        } finally {
            restore(bot, stood);
        }
    }

    /** Every piece the stick would talk about, each hit its full turn. */
    private List<Piece> read(BotSession bot, List<FoundEntitiesRenderer.Entity> hitboxes,
            List<FoundEntitiesRenderer.Entity> displays, Progress progress) {
        Map<String, Piece> byPosition = new LinkedHashMap<>();
        long started = System.currentTimeMillis();

        for (int at = 0; at < hitboxes.size(); at++) {
            FoundEntitiesRenderer.Entity hitbox = hitboxes.get(at);

            progress.report("reading furniture %d of %d".formatted(at + 1, hitboxes.size()), at, hitboxes.size());

            if (hitbox.id() == null) {
                continue;
            }
            Map<String, String> said = hit(bot, hitbox);
            Piece piece = piece(said, hitbox, displays);
            Piece already = byPosition.get(key(piece));

            /*
            A long piece has an interaction box per block it covers, and every one of them describes
            the same piece. Folding them here is what keeps a three-block counter from reading as
            three counters that happen to share a position.
            */
            byPosition.put(key(piece), already == null
                    ? piece
                    : new Piece(already.x(), already.y(), already.z(), already.rotation(), already.variant(),
                            already.models(), already.hitboxes() + 1));
        }
        progress.report("read %d piece(s) in %ds".formatted(byPosition.size(),
                (System.currentTimeMillis() - started) / 1_000), hitboxes.size(), hitboxes.size());

        return List.copyOf(byPosition.values());
    }

    /**
     * One piece hit its full turn, and what each hit put on the action bar.
     *
     * <p>Read once at the end rather than after every hit: the action bar is also where a server's
     * HUD lives, and on a busy one it redraws several times a second, so a read between hits spends
     * as long as the hit did. The feed keeps what was said, and a mark taken before the first hit
     * is what separates this piece's answers from the last piece's.
     */
    private Map<String, String> hit(BotSession bot, FoundEntitiesRenderer.Entity hitbox) {
        ToolSpec attack = catalog.require("attack-entity");

        ToolDispatcher.offerCheck(attack, bot);
        stand(bot, hitbox);

        long mark = bot.feed("actionBar").nextSeq();

        for (int swing = 0; swing < HITS; swing++) {
            if (Boolean.TRUE.equals(remote.call(attack, bot, Map.of("id", hitbox.id())).isError())) {
                break;
            }
        }
        Map<String, String> said = new LinkedHashMap<>();

        for (FeedEntry line : bot.feed("actionBar").since(mark)) {
            Matcher property = SELECTED.matcher(line.rendered());

            while (property.find()) {
                said.put(property.group(1), property.group(2));
            }
        }
        return said;
    }

    /**
     * What the stick said about a piece, as the piece.
     *
     * <p>The stick names its states after the step they take -- {@code x_0_1} and {@code x_0_0_1}
     * both report x, {@code rotation_90} through {@code rotation_0_5} all report the one rotation --
     * so the step is dropped and what is left is the property.
     */
    private static Piece piece(Map<String, String> said, FoundEntitiesRenderer.Entity hitbox,
            List<FoundEntitiesRenderer.Entity> displays) {
        Double x = number(said, "x");
        Double y = number(said, "y");
        Double z = number(said, "z");
        Double rotation = number(said, "rotation");
        String variant = said.get("variant");

        /* A piece the stick would not talk about is still worth listing, at the box that stood in for it. */
        double atX = x == null ? hitbox.position().x() + 0.5 : x;
        double atY = y == null ? hitbox.position().y() : y;
        double atZ = z == null ? hitbox.position().z() + 0.5 : z;

        return new Piece(atX, atY, atZ, rotation, variant, models(atX, atY, atZ, displays), 1);
    }

    /** The models of the displays standing in the block a piece sits in, which is what says what it is. */
    private static List<String> models(double x, double y, double z,
            List<FoundEntitiesRenderer.Entity> displays) {
        List<String> found = new ArrayList<>();

        for (FoundEntitiesRenderer.Entity display : displays) {
            if (display.item() == null || display.item().itemModel() == null) {
                continue;
            }
            if (display.position().x() == (int) Math.floor(x) && display.position().y() == (int) Math.floor(y)
                    && display.position().z() == (int) Math.floor(z)
                    && !found.contains(display.item().itemModel())) {
                found.add(display.item().itemModel());
            }
        }
        return found;
    }

    private static Double number(Map<String, String> said, String property) {
        for (Map.Entry<String, String> entry : said.entrySet()) {
            if (entry.getKey().equals(property) || entry.getKey().startsWith(property + "_")) {
                try {
                    return Double.valueOf(entry.getValue());
                } catch (NumberFormatException notANumber) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String key(Piece piece) {
        return "%.2f/%.2f/%.2f".formatted(piece.x(), piece.y(), piece.z());
    }

    /** The bot within reach of a piece, above it rather than in it. */
    private void stand(BotSession bot, FoundEntitiesRenderer.Entity hitbox) {
        commands.send(bot, "tp %.1f %d %.1f".formatted(hitbox.position().x() + 0.5,
                hitbox.position().y() + STAND_ABOVE, hitbox.position().z() + 0.5));
        Commands.sleep(Commands.POLL_MS);
    }

    /**
     * The bot inside the box, so the client holds the entities in it.
     *
     * <p>An entity list is the client's, and a client only has the chunks it is near. Reading a
     * room from across the map answered with nothing in it and no reason why.
     */
    private void centre(BotSession bot, Region box) {
        commands.send(bot, "tp %d %d %d".formatted(box.minX() + (int) (box.sizeX() / 2), box.maxY() + 1,
                box.minZ() + (int) (box.sizeZ() / 2)));
        Commands.sleep(Commands.POLL_MS);
    }

    /** The entities of one kind that are inside the box, as the bot's own find-entity has them. */
    private List<FoundEntitiesRenderer.Entity> within(BotSession bot, Region box, String type) {
        ToolSpec find = catalog.require("find-entity");

        ToolDispatcher.offerCheck(find, bot);

        Map<String, Object> arguments = new LinkedHashMap<>(box.corners());

        arguments.put("type", type);
        arguments.put("count", SWEEP);

        long deadline = System.currentTimeMillis() + CHUNKS_MS;
        List<FoundEntitiesRenderer.Entity> found = List.of();

        /* The bot has only just been put in the box, and its entities arrive over the next few ticks. */
        while (true) {
            Messages.Result result = remote.fetch(find, bot, arguments).result();

            if (!result.ok() || result.data() == null) {
                throw new IllegalStateException("find-entity over %s failed: %s".formatted(box, result.text()));
            }
            found = MAPPER.convertValue(result.data(), FoundEntitiesRenderer.View.class).entities();

            if (!found.isEmpty() || System.currentTimeMillis() >= deadline) {
                return found;
            }
            Commands.sleep(Commands.POLL_MS);
        }
    }

    /**
     * A debug stick in the bot's hand, or the reason there cannot be one.
     *
     * <p>Given rather than required: the tool is worth calling on a bot that has never held one,
     * and {@code give-item} is how every other composed tool gets what it needs. What cannot be
     * given is the permission -- CraftEngine reads the stick only for a player who can build and
     * holds {@code minecraft.debugstick} -- and that reads as a room whose every piece answered
     * nothing, which is why it is said here instead.
     */
    private String armed(BotSession bot) {
        ToolSpec give = catalog.require("give-item");
        ToolSpec equip = catalog.require("equip-item");

        ToolDispatcher.offerCheck(give, bot);
        ToolDispatcher.offerCheck(equip, bot);

        if (Boolean.TRUE.equals(remote.call(equip, bot, Map.of("itemName", DEBUG_STICK)).isError())) {
            McpSchema.CallToolResult given = remote.call(give, bot, Map.of("itemName", DEBUG_STICK));

            if (Boolean.TRUE.equals(given.isError())) {
                return "the bot could not be given a debug stick, which is what reads a piece of furniture: %s. It needs creative mode and the permission to run /give."
                        .formatted(ToolDispatcher.textOf(given));
            }
            McpSchema.CallToolResult held = remote.call(equip, bot, Map.of("itemName", DEBUG_STICK));

            if (Boolean.TRUE.equals(held.isError())) {
                return "the bot was given a debug stick and could not hold it: %s".formatted(ToolDispatcher.textOf(held));
            }
        }
        return null;
    }

    private String described(List<Piece> pieces, Region box, List<String> notes) {
        List<String> out = new ArrayList<>();
        long known = pieces.stream().filter(Piece::known).count();

        out.add("%d piece(s) of furniture in %s.".formatted(pieces.size(), box));

        for (Piece piece : pieces) {
            out.add("- %s at %s%s%s%s".formatted(
                    piece.models().isEmpty() ? "(a piece whose displays show no model)" : String.join(", ", piece.models()),
                    "%.2f, %.2f, %.2f".formatted(piece.x(), piece.y(), piece.z()),
                    piece.rotation() == null ? "" : ", facing %s".formatted(Text.oneDecimal(piece.rotation())),
                    piece.variant() == null ? "" : ", variant %s".formatted(piece.variant()),
                    piece.hitboxes() == 1 ? "" : " (%d interaction boxes)".formatted(piece.hitboxes())));
        }
        if (known == 0) {
            out.add("None of them answered the debug stick. CraftEngine reads it for a player in creative mode who holds minecraft.debugstick, and it answers for its own furniture only: a room of plain item displays reads like this too.");
        } else if (known < pieces.size()) {
            out.add("%d of them said nothing about themselves, which is what a display that is not CraftEngine furniture does."
                    .formatted(pieces.size() - known));
        }
        out.addAll(notes);

        return Trust.mark(String.join("\n", out));
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
