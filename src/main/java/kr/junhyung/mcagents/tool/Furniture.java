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
import kr.junhyung.mcagents.render.RegionRenderer;
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

    /**
     * How far to the side of a piece the bot stands to hit it.
     *
     * <p>Beside, and never above. CraftEngine answers a swing by casting a ray from the player's
     * eye to the hitbox and asking where it enters, and a ray that starts inside the box enters
     * nowhere -- so a bot standing in a piece hits it, is told it hit it, and is answered with
     * silence. Standing above is standing inside: an interaction box has no collision, so the bot
     * falls through the piece and lands with its eyes at 1.62m, which is inside anything taller
     * than that. That is exactly which pieces went unread: a monitor on a counter and a newspaper
     * rack, while every chair and table answered.
     *
     * <p>Two blocks, because attack-entity walks to anything further than three and a walk is a
     * second spent going nowhere useful.
     */
    private static final double STAND_BESIDE = 2.0;

    /** How many places a piece is hit from before it counts as having nothing to say. */
    private static final int STANCES = 3;

    /** How long one probing swing is given to show up on the action bar. */
    private static final int PROBE_MS = 600;

    /** How long the plugin is given to answer a piece being placed; it answers within a tick. */
    private static final int PLACE_MS = 3_000;

    /** How long a piece that has just been placed is given to reach the client. */
    private static final int PLACED_MS = 3_000;

    /** How many times a piece is hit before it counts as one this bot cannot break. */
    private static final int BREAK_SWINGS = 8;

    /** What the plugin says to an id it does not have. */
    private static final Pattern UNKNOWN_FURNITURE = Pattern.compile("[Uu]nknown furniture");

    /** How long the client is given to have the box's blocks after the bot is put in it. */
    private static final int CHUNKS_MS = 15_000;

    /** How long after the blocks the entities in them are given. */
    private static final int SETTLE_MS = 400;

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

            String late = settled(bot, box);

            if (late != null) {
                return ToolDispatcher.text(late);
            }
            List<FoundEntitiesRenderer.Entity> hitboxes = within(bot, box, "interaction");
            List<FoundEntitiesRenderer.Entity> displays = within(bot, box, "item_display");

            if (hitboxes.isEmpty()) {
                return ToolDispatcher.text(
                        "No furniture in %s: nothing in it has an interaction box, which is what CraftEngine gives a piece to be clicked on. %d item display(s) are in there, and a display with no box is a decoration placed some other way."
                                .formatted(box, displays.size()));
            }
            if (hitboxes.size() > count) {
                notes.add("%d pieces have an interaction box in there and %d were read, nearest the middle of the box first. Read the rest in a box of their own, or raise \"count\"."
                        .formatted(hitboxes.size(), count));
                hitboxes = hitboxes.subList(0, count);
            }

            String stick = armed(bot);

            if (stick != null) {
                return ToolDispatcher.failure(stick);
            }
            return ToolDispatcher.text(described(read(bot, hitboxes, box, displays, notes, progress), box, notes));
        } finally {
            restore(bot, stood);
        }
    }

    /**
     * remove-furniture: pieces taken away again.
     *
     * <p>The plugin has no command for this, and the one thing that does take a piece away is what
     * a player does: hit it with something that is not a debug stick. The stick is the exception
     * because CraftEngine cancels a hit that carries one -- which is what makes reading a room
     * safe, and what makes this have to put the stick down first.
     *
     * <p>place-furniture without this was a tool that could not be taken back. A piece put down in
     * the wrong place had to be killed as a raw entity, which leaves the display behind and the
     * plugin still believing the piece is there.
     */
    public McpSchema.CallToolResult remove(ToolSpec spec, BotSession bot, Map<String, Object> given,
            Progress progress) {
        Map<String, Object> arguments = given == null ? Map.of() : given;
        Region box = Region.of(spec.name(), arguments);
        int count = arguments.get("count") instanceof Number number ? number.intValue() : DEFAULT_COUNT;

        return remote.exclusively(spec, bot, () -> remove(bot, box, count, progress));
    }

    private McpSchema.CallToolResult remove(BotSession bot, Region box, int count, Progress progress) {
        Messages.Position stood = position(bot);

        try {
            centre(bot, box);

            String late = settled(bot, box);

            if (late != null) {
                return ToolDispatcher.text(late);
            }
            List<FoundEntitiesRenderer.Entity> hitboxes = within(bot, box, "interaction");

            if (hitboxes.isEmpty()) {
                return ToolDispatcher.text("Nothing in %s has an interaction box, so there is no furniture there to take away."
                        .formatted(box));
            }
            /* Read before anything is broken, since what a piece was showing is gone with it. */
            List<FoundEntitiesRenderer.Entity> displays = within(bot, box, "item_display");

            if (hitboxes.size() > count) {
                hitboxes = hitboxes.subList(0, count);
            }
            String empty = disarmed(bot);

            if (empty != null) {
                return ToolDispatcher.failure(empty);
            }
            return ToolDispatcher.text(Trust.mark(swung(bot, box, hitboxes, displays, progress)));
        } finally {
            restore(bot, stood);
        }
    }

    private String swung(BotSession bot, Region box, List<FoundEntitiesRenderer.Entity> hitboxes,
            List<FoundEntitiesRenderer.Entity> displays, Progress progress) {
        ToolSpec attack = catalog.require("attack-entity");

        ToolDispatcher.offerCheck(attack, bot);

        int broken = 0;
        List<String> refusals = new ArrayList<>();
        List<String> taken = new ArrayList<>();

        for (int at = 0; at < hitboxes.size(); at++) {
            FoundEntitiesRenderer.Entity hitbox = hitboxes.get(at);

            if (hitbox.id() == null) {
                continue;
            }
            Region where = new Region(hitbox.position().x(), hitbox.position().y(), hitbox.position().z(),
                    hitbox.position().x(), hitbox.position().y(), hitbox.position().z());
            List<String> showing = models(hitbox.position().x() + 0.5, hitbox.position().y(),
                    hitbox.position().z() + 0.5, true, displays);
            boolean gone = false;

            /*
            From each side in turn, the way reading does. A swing is judged by the rotation the
            server holds, so one that was aimed from a place the piece is not visible from lands
            nowhere -- and a piece is not proof against every side just because it survived one.
            */
            for (int stance = 0; stance < STANCES && !gone; stance++) {
                progress.report("taking away %d of %d".formatted(at + 1, hitboxes.size()),
                        at * STANCES + stance, hitboxes.size() * STANCES);
                stand(bot, hitbox, box, stance);

                long mark = listening(bot);

                for (int swing = 0; swing < BREAK_SWINGS; swing++) {
                    if (Boolean.TRUE.equals(remote.call(attack, bot, Map.of("id", hitbox.id())).isError())) {
                        break;
                    }
                    if (within(bot, where, "interaction").stream()
                            .noneMatch(left -> hitbox.id().equals(left.id()))) {
                        broken++;
                        gone = true;
                        showing.stream().filter(model -> !taken.contains(model)).forEach(taken::add);
                        break;
                    }
                }
                /*
                What the server said while it was being hit. A plugin that will not let this piece
                be broken says so here and nowhere else -- the swing itself is answered with
                silence, since an interaction box takes no damage and reports nothing either way.
                */
                for (FeedEntry line : Commands.systemLines(bot, mark)) {
                    if (!refusals.contains(line.rendered())) {
                        refusals.add(line.rendered());
                    }
                }
            }
        }
        /*
        Only what went. Listing every display in the box named pieces that are still standing as
        things that had been taken away, which is the opposite of what the sentence says.
        */
        String what = taken.isEmpty() ? "" : " They were showing %s.".formatted(String.join(", ", taken));

        if (broken == hitboxes.size()) {
            return "Took away %d piece(s) of furniture from %s.%s".formatted(broken, box, what);
        }
        String said = refusals.isEmpty()
                ? " The server said nothing while they were being hit, and a swing at an interaction box is answered with silence either way, so there is nothing here that says why."
                : " While they were being hit the server said: %s".formatted(String.join(" / ", refusals));

        return "Took away %d of %d piece(s) in %s, from %d sides each. The rest are still standing.%s%s"
                .formatted(broken, hitboxes.size(), box, STANCES, said, what);
    }

    /**
     * The mark a piece's hits are judged from, taken once the bot's own teleport has been answered.
     *
     * <p>Marking as soon as the bot had been stood put "Teleported interior to ..." in with the
     * refusals, which is the one thing that list is for. Waiting for the feed to go quiet keeps it
     * out whatever language the server says it in, which matching the sentence would not, and the
     * plugin's own line comes after this anyway: it is an answer to the swing, and the swing has
     * not gone out yet.
     */
    private static long listening(BotSession bot) {
        long deadline = System.currentTimeMillis() + Commands.QUIET_MS;
        long mark = bot.feed("chat").nextSeq();

        while (System.currentTimeMillis() < deadline) {
            Commands.sleep(Commands.POLL_MS);

            long now = bot.feed("chat").nextSeq();

            if (now == mark) {
                return mark;
            }
            mark = now;
        }
        return bot.feed("chat").nextSeq();
    }

    /**
     * The debug stick out of the bot's hands, since a hit that carries one is cancelled before it
     * breaks anything -- which is exactly what read-furniture relies on.
     *
     * <p>Taken away rather than asked about. Nothing here can see what a bot is holding, and the
     * first try at working it out equipped the stick to find out whether it was equipped, which
     * meant it always was. read-furniture gives itself one whenever it needs one, so losing it
     * costs nothing.
     */
    private String disarmed(BotSession bot) {
        long mark = bot.feed("chat").nextSeq();

        commands.send(bot, "clear @s minecraft:debug_stick");

        FeedEntry no = Commands.refused(Commands.systemLines(bot, mark));

        return no == null
                ? null
                : Trust.mark("the debug stick could not be taken out of the bot's hands, and a hit that carries one breaks nothing: /clear came back as \"%s\"."
                        .formatted(no.rendered()));
    }

    /**
     * place-furniture: pieces put down where and facing how the caller said, and read back.
     *
     * <p>The plugin's command takes a location and nothing else, and the location it builds starts
     * from the sender's own: a bot that sends it keeps its world, its yaw and its pitch, and only
     * x, y and z are overwritten. So a piece's facing is set by standing the bot at it, which is
     * also what loads the chunk the piece goes in. The command must never be wrapped in /execute
     * for the same reason -- a proxied sender is not an entity, so the piece would face zero and
     * land in whichever world the server lists first.
     */
    public McpSchema.CallToolResult place(ToolSpec spec, BotSession bot, Map<String, Object> given,
            Progress progress) {
        Map<String, Object> arguments = given == null ? Map.of() : given;
        List<Wanted> wanted = wanted(arguments.get("pieces"));
        boolean verify = !Boolean.FALSE.equals(arguments.get("verify"));

        return remote.exclusively(spec, bot, () -> place(bot, wanted, verify, progress));
    }

    /** One piece as the caller asked for it. */
    private record Wanted(String model, double x, double y, double z, double rotation, String variant) {

        Region block() {
            return new Region((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z),
                    (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        }

        String where() {
            return "%.2f, %.2f, %.2f".formatted(x, y, z);
        }
    }

    private static List<Wanted> wanted(Object given) {
        if (!(given instanceof List<?> pieces) || pieces.isEmpty()) {
            throw new IllegalArgumentException("\"pieces\" is required and holds at least one piece");
        }
        List<Wanted> wanted = new ArrayList<>();

        for (Object piece : pieces) {
            if (!(piece instanceof Map<?, ?> one)) {
                throw new IllegalArgumentException("every piece is an object with model, x, y and z");
            }
            String model = one.get("model") instanceof String named ? named : null;

            if (model == null || !model.contains(":")) {
                throw new IllegalArgumentException(
                        "a piece's \"model\" is a furniture id with its namespace, like default:desk_chair, and one said \"%s\""
                                .formatted(model));
            }
            wanted.add(new Wanted(model, axis(one, "x"), axis(one, "y"), axis(one, "z"),
                    one.get("rotation") instanceof Number turn ? turn.doubleValue() : 0,
                    one.get("variant") instanceof String variant ? variant : null));
        }
        return wanted;
    }

    private static double axis(Map<?, ?> piece, String axis) {
        if (piece.get(axis) instanceof Number value) {
            return value.doubleValue();
        }
        throw new IllegalArgumentException("a piece's \"%s\" is a number, and one had none".formatted(axis));
    }

    private McpSchema.CallToolResult place(BotSession bot, List<Wanted> wanted, boolean verify, Progress progress) {
        Messages.Position stood = position(bot);
        List<String> out = new ArrayList<>();
        int placed = 0;

        try {
            String stick = verify ? armed(bot) : null;

            if (stick != null) {
                return ToolDispatcher.failure(stick);
            }
            for (int at = 0; at < wanted.size(); at++) {
                Wanted piece = wanted.get(at);

                progress.report("placing %d of %d".formatted(at + 1, wanted.size()), at, wanted.size());

                String line = placed(bot, piece, verify);

                out.add(line);
                if (!line.contains("was not placed")) {
                    placed++;
                }
            }
        } finally {
            restore(bot, stood);
        }
        out.addFirst("%d of %d piece(s) placed.".formatted(placed, wanted.size()));

        if (!verify) {
            out.add("Nothing was read back, so what stands there is what the command said it placed rather than what the server holds. A variant name the plugin does not know is accepted in silence and echoed back unchanged.");
        }
        return ToolDispatcher.text(Trust.mark(String.join("\n", out)));
    }

    private String placed(BotSession bot, Wanted piece, boolean verify) {
        /* Standing at the piece is what decides its facing, so a teleport that was refused places it wrong. */
        long mark = bot.feed("chat").nextSeq();

        /*
        Named, because "/tp x y z yaw pitch" is not a form the game has: a rotation may only follow
        a target, and without one the game reads the yaw as the start of a second position and
        refuses the whole command. It did, quietly -- the refusal is an "Incorrect argument", which
        is neither an unknown command nor a missing permission -- and the piece was then placed
        facing wherever the bot happened to be looking.
        */
        commands.send(bot, "tp @s %.2f %.2f %.2f %.2f 0".formatted(piece.x(), piece.y() + STAND_BESIDE,
                piece.z(), piece.rotation()));

        FeedEntry no = Commands.refused(Commands.systemLines(bot, mark));

        if (no != null) {
            return "- %s was not placed at %s: the bot could not be teleported there, and where it stands is what decides which way a piece faces. /tp came back as \"%s\"."
                    .formatted(piece.model(), piece.where(), no.rendered());
        }
        Commands.sleep(Commands.POLL_MS);

        String taken = occupied(bot, piece);

        if (taken != null) {
            return taken;
        }
        mark = bot.feed("chat").nextSeq();
        commands.send(bot, "craftengine debug spawn-furniture %s %s %s %s%s".formatted(
                trimmed(piece.x()), trimmed(piece.y()), trimmed(piece.z()), piece.model(),
                piece.variant() == null ? "" : " " + piece.variant()));

        List<FeedEntry> said = Commands.awaitChat(bot, mark, System.currentTimeMillis() + PLACE_MS,
                Progress.NONE, "the piece to be placed");
        FeedEntry refused = Commands.refused(said);

        if (refused != null) {
            return "- %s was not placed at %s: %s (the permission is ce.command.debug.spawn_furniture, which op has)."
                    .formatted(piece.model(), piece.where(), refused.rendered());
        }
        if (said.stream().anyMatch(line -> UNKNOWN_FURNITURE.matcher(line.rendered()).find())) {
            return "- %s was not placed at %s: the plugin does not know that id. learn-custom-blocks lists what this server has."
                    .formatted(piece.model(), piece.where());
        }
        if (!verify) {
            return "- %s at %s facing %s%s.".formatted(piece.model(), piece.where(),
                    Text.oneDecimal(piece.rotation()),
                    piece.variant() == null ? "" : ", variant " + piece.variant());
        }
        return confirmed(bot, piece);
    }

    /**
     * What the stick says stands there now, against what was asked for.
     *
     * <p>Read rather than taken on trust, because the two things most likely to be wrong are the
     * two the command will not complain about: a variant name the plugin does not know is swapped
     * for the piece's first one without a word, and the command's own reply repeats the name it was
     * given rather than the one it used.
     */
    private String confirmed(BotSession bot, Wanted piece) {
        Region block = piece.block();
        List<FoundEntitiesRenderer.Entity> hitboxes = arriving(bot, block);

        if (hitboxes.isEmpty()) {
            return "- %s at %s: the command complained about nothing and nothing stands in that block, so it did not go down."
                    .formatted(piece.model(), piece.where());
        }
        List<Piece> read = read(bot, hitboxes, block, within(bot, block, "item_display"),
                new ArrayList<>(), Progress.NONE);
        Piece found = read.stream().filter(Piece::known).findFirst().orElse(null);

        if (found == null) {
            return "- %s at %s: it went down and the debug stick said nothing about it, so what stands there is unconfirmed."
                    .formatted(piece.model(), piece.where());
        }
        List<String> off = new ArrayList<>();

        if (Math.abs(found.x() - piece.x()) > 0.005 || Math.abs(found.y() - piece.y()) > 0.005
                || Math.abs(found.z() - piece.z()) > 0.005) {
            off.add("it sits at %.2f, %.2f, %.2f".formatted(found.x(), found.y(), found.z()));
        }
        if (found.rotation() != null && Math.abs(turn(found.rotation()) - turn(piece.rotation())) > 0.01) {
            off.add("it faces %s".formatted(Text.oneDecimal(turn(found.rotation()))));
        }
        if (piece.variant() != null && found.variant() != null && !piece.variant().equals(found.variant())) {
            off.add("its variant is %s, which is what the plugin falls back to when it does not know the name it was given"
                    .formatted(found.variant()));
        }
        String models = found.models().isEmpty() ? "" : " The display there shows %s.".formatted(
                String.join(", ", found.models()));

        return off.isEmpty()
                ? "- %s at %s facing %s%s, read back and matching.%s".formatted(piece.model(), piece.where(),
                        Text.oneDecimal(piece.rotation()),
                        found.variant() == null ? "" : ", variant " + found.variant(), models)
                : "- %s at %s: it went down, and %s.%s".formatted(piece.model(), piece.where(),
                        String.join("; ", off), models);
    }

    /**
     * The hitboxes in a block, waited for.
     *
     * <p>A piece that has just gone down is on the server before it is on the client, and looking
     * straight away found nothing and reported that the piece had not gone down at all -- which was
     * the opposite of what had happened. Looking for a moment tells "not there yet" from "not
     * there", and a block that is genuinely empty costs the wait once.
     */
    private List<FoundEntitiesRenderer.Entity> arriving(BotSession bot, Region block) {
        /*
        A block either side, because a hitbox does not sit in the block its piece does. A chair
        placed at y=3 is given one at y=2 -- the box hangs under the model rather than around it --
        and looking only where the piece is found nothing and reported the piece had not gone down.
        */
        Region around = new Region(block.minX(), block.minY() - 1, block.minZ(),
                block.maxX(), block.maxY() + 1, block.maxZ());
        long deadline = System.currentTimeMillis() + PLACED_MS;

        while (true) {
            List<FoundEntitiesRenderer.Entity> found = within(bot, around, "interaction");

            if (!found.isEmpty() || System.currentTimeMillis() >= deadline) {
                return found;
            }
            Commands.sleep(Commands.POLL_MS);
        }
    }

    /** Refuse a block that already holds a piece, since remove-furniture is what takes one away. */
    private String occupied(BotSession bot, Wanted piece) {
        Region block = piece.block();
        Region around = new Region(block.minX(), block.minY() - 1, block.minZ(),
                block.maxX(), block.maxY() + 1, block.maxZ());

        if (within(bot, around, "interaction").isEmpty()) {
            return null;
        }
        return "- %s was not placed at %s: something with an interaction box already stands in that block. read-furniture says what it is."
                .formatted(piece.model(), piece.where());
    }

    /**
     * Degrees as the server holds them, so 360 and 0 and -360 are one angle.
     *
     * <p>What is read back is reported through this as well. The stick answers a piece placed at
     * 225 with -135, and place-furniture takes 0 to 360, so a room read in order to be copied came
     * back with angles its own tool would refuse.
     */
    private static double turn(double degrees) {
        double wrapped = degrees % 360;

        return wrapped < 0 ? wrapped + 360 : wrapped;
    }

    /** A coordinate without trailing zeroes, which is what the command's own parser reads back. */
    private static String trimmed(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : "%.2f".formatted(value);
    }
    /**
     * Every piece the stick would talk about, each hit its full turn.
     *
     * <p>A piece that said nothing is tried again from another side before it is written off.
     * Silence is not proof there is nothing there -- it is what a bot standing inside a piece gets
     * -- and the retry costs one swing per silent piece per stance, which is nothing beside the
     * fifteen an answering piece takes.
     */
    private List<Piece> read(BotSession bot, List<FoundEntitiesRenderer.Entity> hitboxes, Region box,
            List<FoundEntitiesRenderer.Entity> displays, List<String> notes, Progress progress) {
        Map<Integer, Map<String, String>> said = new LinkedHashMap<>();
        long started = System.currentTimeMillis();
        int rescued = 0;

        for (int stance = 0; stance < STANCES; stance++) {
            for (int at = 0; at < hitboxes.size(); at++) {
                FoundEntitiesRenderer.Entity hitbox = hitboxes.get(at);

                if (hitbox.id() == null || !said.getOrDefault(hitbox.id(), Map.of()).isEmpty()) {
                    continue;
                }
                progress.report(stance == 0
                        ? "reading furniture %d of %d".formatted(at + 1, hitboxes.size())
                        : "hitting %d silent piece(s) from another side".formatted(silent(hitboxes, said)),
                        stance * hitboxes.size() + at, STANCES * hitboxes.size());

                Map<String, String> answered = hit(bot, hitbox, box, stance);

                said.put(hitbox.id(), answered);
                if (!answered.isEmpty() && stance > 0) {
                    rescued++;
                }
            }
            if (silent(hitboxes, said) == 0) {
                break;
            }
        }
        if (rescued > 0) {
            notes.add("%d piece(s) answered only after being hit from another side.".formatted(rescued));
        }
        progress.report("read %d piece(s) in %ds".formatted(said.size(),
                (System.currentTimeMillis() - started) / 1_000), STANCES * hitboxes.size(), STANCES * hitboxes.size());

        return folded(hitboxes, said, displays);
    }

    private static int silent(List<FoundEntitiesRenderer.Entity> hitboxes, Map<Integer, Map<String, String>> said) {
        return (int) hitboxes.stream()
                .filter(hitbox -> hitbox.id() != null && said.getOrDefault(hitbox.id(), Map.of()).isEmpty())
                .count();
    }

    /**
     * The hitboxes as pieces.
     *
     * <p>Folded after every stance and not during: a long piece has an interaction box per block it
     * covers and every one of them describes the same piece, but a box that has said nothing is
     * only known by where it sits, so folding it early would file it under a position the piece it
     * belongs to does not have.
     */
    private static List<Piece> folded(List<FoundEntitiesRenderer.Entity> hitboxes,
            Map<Integer, Map<String, String>> said, List<FoundEntitiesRenderer.Entity> displays) {
        Map<String, Piece> byPosition = new LinkedHashMap<>();

        for (FoundEntitiesRenderer.Entity hitbox : hitboxes) {
            if (hitbox.id() == null) {
                continue;
            }
            Piece piece = piece(said.getOrDefault(hitbox.id(), Map.of()), hitbox, displays);
            Piece already = byPosition.get(key(piece));

            byPosition.put(key(piece), already == null
                    ? piece
                    : new Piece(already.x(), already.y(), already.z(), already.rotation(), already.variant(),
                            already.models(), already.hitboxes() + 1));
        }
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
    private Map<String, String> hit(BotSession bot, FoundEntitiesRenderer.Entity hitbox, Region box, int stance) {
        ToolSpec attack = catalog.require("attack-entity");

        ToolDispatcher.offerCheck(attack, bot);
        stand(bot, hitbox, box, stance);

        long mark = bot.feed("actionBar").nextSeq();

        /*
        One swing first. A swing CraftEngine did not answer left the stick where it was, so nothing
        is lost by asking before committing -- and a display that is not furniture at all costs one
        swing instead of fifteen.
        */
        if (Boolean.TRUE.equals(remote.call(attack, bot, Map.of("id", hitbox.id())).isError())
                || said(bot, mark, PROBE_MS).isEmpty()) {
            return Map.of();
        }
        for (int swing = 1; swing < HITS; swing++) {
            if (Boolean.TRUE.equals(remote.call(attack, bot, Map.of("id", hitbox.id())).isError())) {
                break;
            }
        }
        return said(bot, mark, 0);
    }

    /**
     * What the stick has said since a mark.
     *
     * <p>Read from the feed rather than after every swing: the action bar is also where a server's
     * HUD lives, and on a busy one it redraws several times a second, so a read between swings
     * costs as long as the swing did.
     */
    private Map<String, String> said(BotSession bot, long mark, int waitMs) {
        long deadline = System.currentTimeMillis() + waitMs;

        while (true) {
            Map<String, String> found = new LinkedHashMap<>();

            for (FeedEntry line : bot.feed("actionBar").since(mark)) {
                Matcher property = SELECTED.matcher(line.rendered());

                while (property.find()) {
                    found.put(property.group(1), property.group(2));
                }
            }
            if (!found.isEmpty() || System.currentTimeMillis() >= deadline) {
                return found;
            }
            Commands.sleep(Commands.POLL_MS);
        }
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

        return new Piece(atX, atY, atZ, rotation, variant, models(atX, atY, atZ, y == null, displays), 1);
    }

    /**
     * The models of the displays standing in the block a piece sits in, which is what says what it
     * is.
     *
     * <p>When the stick said nothing the block is the hitbox's rather than the piece's, and a
     * hitbox hangs under its piece, so the block above counts too. Without that every silent piece
     * was listed as showing no model -- and a silent piece is the one case where the model is all
     * there is to name it by.
     */
    private static List<String> models(double x, double y, double z, boolean fromHitbox,
            List<FoundEntitiesRenderer.Entity> displays) {
        List<String> found = new ArrayList<>();
        int block = (int) Math.floor(y);

        for (FoundEntitiesRenderer.Entity display : displays) {
            if (display.item() == null || display.item().itemModel() == null) {
                continue;
            }
            int above = display.position().y() - block;

            if (display.position().x() == (int) Math.floor(x)
                    && display.position().z() == (int) Math.floor(z)
                    && (above == 0 || (fromHitbox && above == 1))
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

    /**
     * The bot beside a piece, facing in from the room rather than standing in it.
     *
     * <p>Which side is decided by where the rest of the box is, so a piece against a wall is
     * approached from the open side. A stance after the first turns that quarter of a turn, which
     * is what gives a piece the bot could not get outside of a second and a third chance.
     */
    private void stand(BotSession bot, FoundEntitiesRenderer.Entity hitbox, Region box, int stance) {
        double x = hitbox.position().x() + 0.5;
        double z = hitbox.position().z() + 0.5;
        double towardX = box.minX() + box.sizeX() / 2.0 - x;
        double towardZ = box.minZ() + box.sizeZ() / 2.0 - z;
        double away = Math.hypot(towardX, towardZ);

        /* A piece in the very middle of the box has no "in" to face, so it is approached from north. */
        double dirX = away < 1e-3 ? 0 : towardX / away;
        double dirZ = away < 1e-3 ? 1 : towardZ / away;

        for (int turn = 0; turn < stance; turn++) {
            double spun = dirX;
            dirX = -dirZ;
            dirZ = spun;
        }
        commands.send(bot, "tp %.2f %d %.2f".formatted(x + dirX * STAND_BESIDE, hitbox.position().y(),
                z + dirZ * STAND_BESIDE));
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

        Messages.Result result = remote.fetch(find, bot, arguments).result();

        if (!result.ok() || result.data() == null) {
            throw new IllegalStateException("find-entity over %s failed: %s".formatted(box, result.text()));
        }
        return MAPPER.convertValue(result.data(), FoundEntitiesRenderer.View.class).entities();
    }

    /**
     * Wait until the client holds the box, or say it never did.
     *
     * <p>Proved with blocks and not with entities. An empty entity list is what a box that has not
     * arrived looks like and also what an empty box looks like, and reading one as the other cost
     * thirty seconds every time a genuinely bare room was read. read-region counts the blocks the
     * client has not got, which tells those two apart exactly.
     */
    private String settled(BotSession bot, Region box) {
        ToolSpec readRegion = catalog.require("read-region");

        ToolDispatcher.offerCheck(readRegion, bot);

        Region floor = new Region(box.minX(), box.minY(), box.minZ(), box.maxX(), box.minY(), box.maxZ());
        Map<String, Object> arguments = new LinkedHashMap<>(floor.corners());

        arguments.put("includeAir", true);

        long deadline = System.currentTimeMillis() + CHUNKS_MS;

        while (true) {
            Messages.Result result = remote.fetch(readRegion, bot, arguments).result();

            if (result.ok() && result.data() != null
                    && MAPPER.convertValue(result.data(), RegionRenderer.View.class).missing() == 0) {
                /* The entities of a chunk arrive a few ticks after its blocks do. */
                Commands.sleep(SETTLE_MS);
                return null;
            }
            if (System.currentTimeMillis() >= deadline) {
                return "The chunks of %s never arrived at the client in the %ds after the bot was put in it, so nothing there could be read."
                        .formatted(box, CHUNKS_MS / 1_000);
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
                    piece.rotation() == null ? "" : ", facing %s".formatted(Text.oneDecimal(turn(piece.rotation()))),
                    piece.variant() == null ? "" : ", variant %s".formatted(piece.variant()),
                    piece.hitboxes() == 1 ? "" : " (%d interaction boxes)".formatted(piece.hitboxes())));
        }
        if (known == 0) {
            out.add("None of them answered the debug stick. CraftEngine reads it for a player in creative mode who holds minecraft.debugstick, and it answers for its own furniture only: a room of plain item displays reads like this too.");
        } else if (known < pieces.size()) {
            out.add("%d of them said nothing from any of the %d sides they were hit from: a display that is not CraftEngine furniture reads like this, and so does a piece the bot could not get outside of."
                    .formatted(pieces.size() - known, STANCES));
        }
        out.addAll(notes);

        return Trust.mark(String.join("\n", out));
    }

    /**
     * Where the bot is, asked rather than remembered.
     *
     * <p>A status is pushed every so often, so one read just after a teleport says the bot is still
     * where it was -- and a tool that puts the bot back from that puts it somewhere it never was.
     */
    private Messages.Position position(BotSession bot) {
        ToolSpec getPosition = catalog.require("get-position");

        if (!bot.supports(getPosition.name())) {
            Messages.Status status = bot.status();

            return status == null ? null : status.position();
        }
        Messages.Result result = remote.fetch(getPosition, bot, Map.of()).result();

        if (result.ok() && result.data() instanceof Map<?, ?> data && data.get("position") instanceof Map<?, ?> at
                && at.get("x") instanceof Number x && at.get("y") instanceof Number y
                && at.get("z") instanceof Number z) {
            return new Messages.Position(x.doubleValue(), y.doubleValue(), z.doubleValue());
        }
        return null;
    }

    private void restore(BotSession bot, Messages.Position stood) {
        if (stood != null) {
            commands.send(bot, "tp %.2f %.2f %.2f".formatted(stood.x(), stood.y(), stood.z()));
        }
    }
}
