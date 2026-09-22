package kr.junhyung.mcagents.tool;

import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import kr.junhyung.mcagents.bot.BotSession;
import kr.junhyung.mcagents.bot.FeedEntry;
import kr.junhyung.mcagents.catalog.Catalog;
import kr.junhyung.mcagents.catalog.ToolSpec;
import kr.junhyung.mcagents.protocol.Messages;
import kr.junhyung.mcagents.render.RegionRenderer;
import kr.junhyung.mcagents.render.Text;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reading a box bigger than a bot reads at once, and putting a kept one down.
 *
 * <p>A bot answers read-region for what its client holds, which is the chunks around it, sixty-four
 * blocks a side and thirty-two thousand blocks a call. A town square is neither. So a box past
 * that is walked: cut into tiles the bot can read, the bot teleported to each tile it does not
 * already hold, every tile read and laid into one snapshot, and the snapshot kept under an id
 * rather than answered -- a million characters is not an answer anybody reads.
 *
 * <p>Writing is the same walk the other way. The snapshot is cut into the fewest boxes of one
 * block each, the bot is brought to each tile so the server has its chunks loaded, one /fill is
 * sent a box, and the tile is read back and compared with what was meant to be there. The bot is
 * put back where it stood when it is over.
 *
 * <p>Neither needs a plugin: /tp and /fill are the game's own, and both need the permission to run
 * them, which is the one thing said when they are refused.
 */
@Component
public class RegionSurvey {

    /** The most a box may span on an axis, and hold, when it is read in tiles. */
    public static final int MAX_SPAN = 512;

    public static final long MAX_BLOCKS = 4_194_304;

    /** A tile's side on the ground: what one call spans, and inside any view distance worth playing at. */
    static final int TILE = Region.MAX_SPAN;

    /** How long a whole survey, or a whole write, may take. */
    static final long PATIENCE_MS = 600_000;

    /** How long a tile is given to arrive after the bot was teleported to it. */
    static final int CHUNKS_MS = 15_000;

    /** How long the first /fill is given to be refused, before the rest are sent without waiting. */
    static final int FIRST_FILL_MS = 3_000;

    /** How long a tile that was put down is given to reach the client before its readback is final. */
    static final int SETTLE_MS = 5_000;

    /** What /fill or //set says when it did nothing it was asked, apart from a refusal of the command itself. */
    private static final Pattern FILL_COMPLAINT = Pattern.compile(
            "(too many blocks|not loaded|cannot be placed|unknown block|expected|incorrect argument|outside allowed region|not permitted|does not exist)",
            Pattern.CASE_INSENSITIVE);

    /** What the server says when an edit has landed: the game's /fill, WorldEdit's //set, and FastAsyncWorldEdit's. */
    private static final Pattern EDIT_DONE = Pattern.compile(
            "(successfully filled|blocks have been changed|operation completed)", Pattern.CASE_INSENSITIVE);

    /** How long //pos1 is given to be acknowledged, which is what decides that WorldEdit is there. */
    static final int SELECTION_MS = 3_000;

    /** How long a tile's edits are given to finish before it is read back, once the server has been answering. */
    static final int EDITS_MS = 60_000;

    /** How many differing blocks a readback names before it only counts them. */
    private static final int NAMED_DIFFERENCES = 5;

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .build();

    private static final RegionRenderer RENDERER = new RegionRenderer();

    private final RemoteTools remote;
    private final Commands commands;
    private final RegionStore store;
    private final Catalog catalog;
    private final CustomBlocks customBlocks;

    public RegionSurvey(RemoteTools remote, Commands commands, RegionStore store, Catalog catalog, CustomBlocks customBlocks) {
        this.remote = remote;
        this.commands = commands;
        this.store = store;
        this.catalog = catalog;
        this.customBlocks = customBlocks;
    }

    /** read-region: one call when the box fits one, a walk when it does not, and a kept region either way. */
    public McpSchema.CallToolResult read(ToolSpec spec, BotSession bot, Map<String, Object> given, Progress progress) {
        Map<String, Object> arguments = given == null ? Map.of() : given;
        Region box = Region.corners(spec.name(), arguments).bounded(spec.name(), MAX_SPAN, MAX_BLOCKS);
        String name = ToolDispatcher.stringArg(arguments, "name");
        boolean includeAir = !Boolean.FALSE.equals(arguments.get("includeAir"));

        if (box.fitsOneCall()) {
            return once(spec, bot, box, name, includeAir);
        }
        return remote.exclusively(spec, bot, () -> walked(spec, bot, box, name, includeAir, progress));
    }

    private McpSchema.CallToolResult once(ToolSpec spec, BotSession bot, Region box, String name, boolean includeAir) {
        RegionRenderer.View view = tile(spec, bot, box);
        long unread = (long) view.missing() + view.outside();
        long spelled = view.runs().stream().mapToLong(RegionRenderer.View.Run::count).sum();

        /*
        Answered as read, and not kept: a run stream with a gap in it says nothing about where the
        blocks after the gap sit, so there is no box to keep. What the bot said stands, in the shape
        it always had, with its own reasons for the gap -- and a bot whose runs do not add up to the
        box at all is answered the same way, since the renderer says what is wrong with them.
        */
        if (unread > 0 || spelled != box.blocks()) {
            return ToolDispatcher.text(RENDERER.render(includeAir ? view : withoutAir(view))
                    + (unread > 0
                            ? "\nNot kept as a region: %d of its blocks were not read.".formatted(unread)
                            : "\nNot kept as a region: the runs spell out %d of its %d blocks.".formatted(spelled, box.blocks())));
        }

        Snapshot kept = Snapshot.blank(store.fresh(), name, box, Instant.now(), origin(bot))
                .with(box, view.palette(), view.runs());
        kept = kept.withPalette(customBlocks.translate(bot, kept.palette()));
        List<String> evicted = store.keep(kept);

        return ToolDispatcher.text(RENDERER.render(kept.view(box, includeAir)) + "\n" + kept(kept, evicted, null));
    }

    private McpSchema.CallToolResult walked(ToolSpec spec, BotSession bot, Region box, String name, boolean includeAir,
            Progress progress) {
        long started = System.currentTimeMillis();
        long deadline = started + PATIENCE_MS;
        Snapshot kept = Snapshot.blank(store.fresh(), name, box, Instant.now(), origin(bot));
        Walk walk = new Walk(position(bot));
        List<Region> tiles = tiles(box);
        List<String> notes = new ArrayList<>();
        int done = 0;

        try {
            for (Region tile : tiles) {
                if (System.currentTimeMillis() > deadline) {
                    notes.add("Stopped after %d of %d tiles: %d minutes is as long as one read goes on for."
                            .formatted(done, tiles.size(), PATIENCE_MS / 60_000));
                    break;
                }
                progress.report("reading tile %d of %d, %s".formatted(done + 1, tiles.size(), tile.extent()),
                        System.currentTimeMillis() - started, PATIENCE_MS);

                String refused = arrive(spec, bot, tile, walk);

                if (refused != null) {
                    notes.add(refused);
                    break;
                }
                for (Region slab : slabs(tile)) {
                    RegionRenderer.View view = tile(spec, bot, slab);

                    if (view.missing() == 0 && view.outside() == 0) {
                        kept = kept.with(slab, view.palette(), view.runs());
                    } else if (view.outside() > 0) {
                        notes.add("%s is partly past the top or bottom of the world, and %d blocks of it were left unread."
                                .formatted(slab, slab.blocks()));
                    } else {
                        notes.add("%s never arrived at the client, and %d blocks of it were left unread."
                                .formatted(slab, slab.blocks()));
                    }
                }
                done++;
            }
        } finally {
            restore(bot, walk);
        }

        kept = kept.withPalette(customBlocks.translate(bot, kept.palette()));
        List<String> evicted = store.keep(kept);
        String body = RENDERER.render(kept.view(box, includeAir));
        String how = "read in %d tile(s) in %ds".formatted(done, (System.currentTimeMillis() - started) / 1_000);

        return ToolDispatcher.text(String.join("\n", body, kept(kept, evicted, how)) + notes(notes));
    }

    /** write-region: a kept region put down, one /fill a box, and read back. */
    public McpSchema.CallToolResult write(ToolSpec spec, BotSession bot, Map<String, Object> given, Progress progress) {
        Map<String, Object> arguments = given == null ? Map.of() : given;
        Snapshot source = store.require(required(arguments, "region"));
        Object at = arguments.get("at");
        boolean withAir = Boolean.TRUE.equals(arguments.get("pasteAir"));
        boolean verify = !Boolean.FALSE.equals(arguments.get("verify"));
        String via = ToolDispatcher.stringArg(arguments, "via");

        if (via != null && !Via.NAMES.contains(via)) {
            throw new IllegalArgumentException("\"via\" is \"%s\", and it is one of %s".formatted(via, Via.NAMES));
        }

        if (at instanceof Map<?, ?>) {
            Region corner = Region.corners(spec.name(), Map.of("from", at, "to", at));
            source = source.movedTo(corner.minX(), corner.minY(), corner.minZ());
        } else if (at != null) {
            throw new IllegalArgumentException("\"%s\" needs \"at\" as an object with x, y and z".formatted(spec.name()));
        }

        Snapshot placed = source;

        return remote.exclusively(spec, bot, () -> put(spec, bot, placed, withAir, verify, via == null ? "auto" : via, progress));
    }

    /** How a region is put down: WorldEdit's //set, the game's /fill, or whichever the server offers. */
    private static final class Via {

        static final List<String> NAMES = List.of("auto", "worldedit", "fill");
    }

    private McpSchema.CallToolResult put(ToolSpec spec, BotSession bot, Snapshot source, boolean withAir,
            boolean verify, String via, Progress progress) {
        long started = System.currentTimeMillis();
        long deadline = started + PATIENCE_MS;
        Region box = source.box();
        Walk walk = new Walk(position(bot));
        List<Region> tiles = tiles(box);
        List<String> notes = new ArrayList<>();
        List<String> differences = new ArrayList<>();
        Tally tally = new Tally(bot.feed("chat").nextSeq());
        long differing = 0;
        long compared = 0;
        long blocks = 0;
        long custom = 0;
        int sent = 0;
        int done = 0;
        boolean worldEdit;

        /*
        Decided once, before anything is put down: a region that went half through one and half
        through the other would be two edits with two ways of being undone.
        */
        try {
            worldEdit = switch (via) {
                case "worldedit" -> {
                    if (!worldEditAnswers(bot, box)) {
                        throw new IllegalStateException(
                                "via is \"worldedit\", and //pos1 was not acknowledged: WorldEdit or FastAsyncWorldEdit is not on this server, or the bot may not run it.");
                    }
                    yield true;
                }
                case "fill" -> false;
                default -> worldEditAnswers(bot, box);
            };
        } catch (IllegalStateException refused) {
            return ToolDispatcher.failure(refused.getMessage());
        }

        try {
            for (Region tile : tiles) {
                if (System.currentTimeMillis() > deadline) {
                    notes.add("Stopped after %d of %d tiles: %d minutes is as long as one write goes on for."
                            .formatted(done, tiles.size(), PATIENCE_MS / 60_000));
                    break;
                }

                String refused = arrive(spec, bot, tile, walk);

                if (refused != null) {
                    notes.add(refused);
                    break;
                }

                List<Mesh.Box> boxes = Mesh.boxes(source, tile, withAir);
                int tileSent = 0;

                for (Mesh.Box piece : boxes) {
                    String block = source.palette().get(piece.block());

                    /* /fill places a vanilla block, and the look-alike of a custom block is not the custom block. */
                    if (!worldEdit && CustomBlocks.isCustom(block)) {
                        custom += piece.box().blocks();
                        continue;
                    }

                    progress.report("tile %d of %d: %s %d of %d".formatted(done + 1, tiles.size(),
                            worldEdit ? "//set" : "/fill", tileSent + 1, boxes.size()),
                            System.currentTimeMillis() - started, PATIENCE_MS);

                    for (String command : worldEdit ? worldEditCommands(piece, block) : List.of(fill(piece, block))) {
                        McpSchema.CallToolResult refusal = commands.send(bot, command);

                        if (refusal != null) {
                            return refusal;
                        }
                    }
                    sent++;
                    tileSent++;
                    blocks += piece.box().blocks();

                    /*
                    The first command is the one that says whether the bot may run it at all; the
                    rest are sent as fast as the bot answers and the server's complaints tallied as
                    they arrive, since the chat feed keeps two hundred lines and a wall is more.
                    */
                    List<FeedEntry> said = sent == 1
                            ? Commands.awaitChat(bot, tally.mark, System.currentTimeMillis() + FIRST_FILL_MS, Progress.NONE,
                                    worldEdit ? "the first //set" : "the first /fill")
                            : Commands.systemLines(bot, tally.mark);
                    FeedEntry no = Commands.refused(said);

                    if (no != null) {
                        return ToolDispatcher.failure(Trust.mark(worldEdit
                                ? "//set came back as \"%s\". write-region puts a region down through WorldEdit when the server has it, which needs the permission to run it."
                                        .formatted(no.rendered())
                                : "/fill came back as \"%s\". write-region puts a region down with the game's own /fill, which needs the permission to run it (op) on this server."
                                        .formatted(no.rendered())));
                    }
                    tally.take(said, bot);
                }

                /*
                Every edit of the tile answered before it is read back: FastAsyncWorldEdit runs a
                //set on a thread of its own and says so when it is done, and reading a tile back
                under an edit still going up called the write wrong. Waited for only where the
                server has been answering at all, so a server with command feedback off is not
                waited on for nothing.
                */
                if (tileSent > 0 && tally.completed > 0) {
                    long settled = System.currentTimeMillis() + EDITS_MS;
                    while (tally.completed < sent && System.currentTimeMillis() < settled) {
                        Commands.sleep(Commands.POLL_MS);
                        tally.take(Commands.systemLines(bot, tally.mark), bot);
                    }
                }

                if (verify) {
                    progress.report("tile %d of %d: reading it back".formatted(done + 1, tiles.size()),
                            System.currentTimeMillis() - started, PATIENCE_MS);

                    Readback back = readBack(spec, bot, source, tile, withAir, worldEdit);

                    compared += back.compared;
                    differing += back.differing;
                    notes.addAll(back.notes);
                    for (String difference : back.differences) {
                        if (differences.size() < NAMED_DIFFERENCES) {
                            differences.add(difference);
                        }
                    }
                }
                done++;
            }
        } finally {
            restore(bot, walk);
        }

        List<String> out = new ArrayList<>();

        out.add("Put region %s down over %s: %d blocks in %d %s across %d tile(s) in %ds%s."
                .formatted(source.id(), box, blocks, sent, worldEdit ? "WorldEdit edit(s)" : "/fill command(s)", done,
                        (System.currentTimeMillis() - started) / 1_000,
                        withAir ? ", air included" : ", air in the region left what was there"));
        if (custom > 0) {
            out.add("%d of its blocks are custom blocks, which /fill cannot place -- it would put down the vanilla block they look like -- so they were left out. A server with WorldEdit places them; say via \"worldedit\" to insist on it."
                    .formatted(custom));
        }
        if (tally.complaints > 0) {
            out.add(Trust.mark("The server complained about %d of them, the first being \"%s\"."
                    .formatted(tally.complaints, tally.complaint)));
        }
        if (verify && compared > 0) {
            out.add(differing == 0
                    ? "Read back: all %d blocks are as the region has them.".formatted(compared)
                    : "Read back: %d of %d blocks differ from the region, for example %s."
                            .formatted(differing, compared, String.join("; ", differences)));
        } else if (verify) {
            out.add("Nothing was read back.");
        }
        out.addAll(notes);

        return ToolDispatcher.text(String.join("\n", out));
    }

    /** What the server has said about the edits so far: how many it finished, and what it complained of. */
    private static final class Tally {

        long mark;
        int completed;
        int complaints;
        String complaint;

        Tally(long mark) {
            this.mark = mark;
        }

        void take(List<FeedEntry> said, BotSession bot) {
            for (FeedEntry line : said) {
                if (EDIT_DONE.matcher(line.rendered()).find()) {
                    completed++;
                } else if (FILL_COMPLAINT.matcher(line.rendered()).find()) {
                    complaints++;
                    complaint = complaint == null ? line.rendered() : complaint;
                }
            }
            mark = bot.feed("chat").nextSeq();
        }
    }

    /**
     * Whether WorldEdit is on the server and answers the bot: //pos1 is acknowledged on the tick,
     * and a server without the plugin answers it as an unknown command.
     */
    private boolean worldEditAnswers(BotSession bot, Region box) {
        long mark = bot.feed("chat").nextSeq();

        if (commands.send(bot, "//pos1 " + box.lowerCorner()) != null) {
            return false;
        }

        List<FeedEntry> said = Commands.awaitChat(bot, mark, System.currentTimeMillis() + SELECTION_MS, Progress.NONE, "//pos1");

        return !said.isEmpty() && Commands.refused(said) == null;
    }

    /** One box as WorldEdit takes it: the two corners, then the pattern over the selection. */
    static List<String> worldEditCommands(Mesh.Box piece, String block) {
        Region box = piece.box();

        return List.of("//pos1 " + box.lowerCorner(), "//pos2 " + box.upperCorner(), "//set " + block);
    }

    private record Readback(long compared, long differing, List<String> differences, List<String> notes) {}

    /**
     * A tile read back and compared with what was put down, once the client has caught up.
     *
     * <p>A /fill lands on the server and reaches the client a tick or more later, so a readback
     * taken as soon as the last command was acknowledged found the last boxes still air and
     * called the write wrong. The tile is read again until nothing differs or the settling time is
     * up, and what is reported is the last reading: a difference that outlasts the wait is real.
     */
    private Readback readBack(ToolSpec spec, BotSession bot, Snapshot source, Region tile, boolean withAir,
            boolean worldEdit) {
        long deadline = System.currentTimeMillis() + SETTLE_MS;
        Readback last;

        while (true) {
            last = compare(spec, bot, source, tile, withAir);

            if (last.differing == 0 || System.currentTimeMillis() >= deadline) {
                return last;
            }
            Commands.sleep(Commands.POLL_MS);
        }
    }

    private Readback compare(ToolSpec spec, BotSession bot, Snapshot source, Region tile, boolean withAir) {
        List<String> differences = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        long compared = 0;
        long differing = 0;

        for (Region slab : slabs(tile)) {
            RegionRenderer.View view = tile(spec, bot, slab);

            if (view.missing() > 0 || view.outside() > 0) {
                notes.add("%s could not be read back: %d blocks of it never arrived at the client."
                        .formatted(slab, (long) view.missing() + view.outside()));
                continue;
            }

            /* Named as the region names them, so a custom block put down reads back as itself and not as its look. */
            Snapshot back = Snapshot.blank("", null, slab, Instant.now(), "")
                    .with(slab, customBlocks.translate(bot, view.palette()), view.runs());

            for (int y = slab.minY(); y <= slab.maxY(); y++) {
                for (int z = slab.minZ(); z <= slab.maxZ(); z++) {
                    for (int x = slab.minX(); x <= slab.maxX(); x++) {
                        String wanted = source.blockAt(x, y, z);

                        if (wanted == null || (!withAir && RegionRenderer.isAir(wanted))) {
                            continue;
                        }
                        compared++;

                        String is = back.blockAt(x, y, z);

                        if (!wanted.equals(is)) {
                            differing++;
                            if (differences.size() < NAMED_DIFFERENCES) {
                                differences.add("%s wanted %s and is %s".formatted(Text.block(x, y, z), wanted, is));
                            }
                        }
                    }
                }
            }
        }
        return new Readback(compared, differing, differences, notes);
    }

    /**
     * Bring the bot where it can read a tile, if it is not there already.
     *
     * <p>Asked first, moved second: a bot that already holds the chunks -- it is standing in them,
     * or the box is a room and not a town -- is never moved at all, so a small read near the bot
     * stays what it was. Otherwise /tp puts it over the tile's middle, above the box, and the tile
     * is asked for again until the client has it or the wait runs out.
     *
     * @return why the bot could not be brought there, or null when the tile is held
     */
    private String arrive(ToolSpec spec, BotSession bot, Region tile, Walk walk) {
        Region probe = slabs(tile).getFirst();

        if (tile(spec, bot, probe).missing() == 0) {
            return null;
        }

        int x = tile.minX() + (int) (tile.sizeX() / 2);
        int z = tile.minZ() + (int) (tile.sizeZ() / 2);
        int y = tile.maxY() + 1;
        long mark = bot.feed("chat").nextSeq();
        McpSchema.CallToolResult sent = commands.send(bot, "tp %d %d %d".formatted(x, y, z));

        walk.moved = true;
        if (sent != null) {
            return "The bot could not be sent to %s: %s".formatted(Text.block(x, y, z), ToolDispatcher.textOf(sent));
        }

        long deadline = System.currentTimeMillis() + CHUNKS_MS;

        while (System.currentTimeMillis() < deadline) {
            if (tile(spec, bot, probe).missing() == 0) {
                return null;
            }

            FeedEntry no = Commands.refused(Commands.systemLines(bot, mark));

            if (no != null) {
                /* A /tp that was refused moved nothing, and the one that would put the bot back would be refused too. */
                walk.moved = false;
                return Trust.mark("The bot could not be teleported to %s: /tp came back as \"%s\". A box past what one call reads is walked by teleporting the bot to each tile, which needs the permission to run /tp (op); without it, read boxes of at most %d blocks a side and %d blocks from where the bot stands."
                        .formatted(Text.block(x, y, z), no.rendered(), Region.MAX_SPAN, Region.MAX_BLOCKS));
            }
            Commands.sleep(Commands.POLL_MS);
        }
        return "The chunks of %s never arrived at the client in the %ds after the bot was teleported to %s, so it and the tiles after it were left unread."
                .formatted(tile, CHUNKS_MS / 1_000, Text.block(x, y, z));
    }

    /** Where the bot stood before the walk, and whether the walk moved it. */
    private static final class Walk {

        final Messages.Position stood;
        boolean moved;

        Walk(Messages.Position stood) {
            this.stood = stood;
        }
    }

    /**
     * Back to where the bot stood, when it was moved and that is known.
     *
     * <p>Decided from what this walk sent and not from where the status says the bot is now: a
     * status is pushed every so often, and one read before the last teleport had landed would say
     * the bot is home already and leave it on the far side of the box.
     */
    private void restore(BotSession bot, Walk walk) {
        if (walk.stood == null || !walk.moved) {
            return;
        }
        commands.send(bot, "tp %.2f %.2f %.2f".formatted(walk.stood.x(), walk.stood.y(), walk.stood.z()));
    }

    /** One read of a box that fits one, as the bot answered it. */
    private RegionRenderer.View tile(ToolSpec spec, BotSession bot, Region box) {
        ToolSpec readRegion = catalog.require("read-region");

        ToolDispatcher.offerCheck(readRegion, bot);

        Map<String, Object> arguments = new java.util.LinkedHashMap<>(box.corners());

        arguments.put("includeAir", true);

        Messages.Result result = remote.fetch(readRegion, bot, arguments).result();

        if (!result.ok()) {
            throw new IllegalStateException("read-region over %s failed: %s".formatted(box,
                    result.text() != null ? result.text()
                            : result.error() != null ? result.error().message() : "the bot gave no reason"));
        }
        if (result.data() == null) {
            throw new IllegalStateException(
                    "bot \"%s\" (kind: %s) answered read-region without the data the catalogue says it sends."
                            .formatted(bot.name(), bot.kind()));
        }
        return MAPPER.convertValue(result.data(), RegionRenderer.View.class);
    }

    /** The box cut on the ground into squares one call spans, whole height each. */
    static List<Region> tiles(Region box) {
        List<Region> tiles = new ArrayList<>();

        for (int z = box.minZ(); z <= box.maxZ(); z += TILE) {
            for (int x = box.minX(); x <= box.maxX(); x += TILE) {
                tiles.add(new Region(x, box.minY(), z,
                        Math.min(x + TILE - 1, box.maxX()), box.maxY(), Math.min(z + TILE - 1, box.maxZ())));
            }
        }
        return tiles;
    }

    /** A tile cut by height into what one call holds, so a full tile is eight blocks tall a slab. */
    static List<Region> slabs(Region tile) {
        int height = (int) Math.min(Region.MAX_SPAN, Region.MAX_BLOCKS / (tile.sizeX() * tile.sizeZ()));
        List<Region> slabs = new ArrayList<>();

        for (int y = tile.minY(); y <= tile.maxY(); y += height) {
            slabs.add(new Region(tile.minX(), y, tile.minZ(), tile.maxX(), Math.min(y + height - 1, tile.maxY()), tile.maxZ()));
        }
        return slabs;
    }

    static String fill(Mesh.Box piece, String block) {
        Region box = piece.box();

        return "fill %d %d %d %d %d %d %s".formatted(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(), block);
    }

    /** The view with air taken out of it, which is what a caller who said includeAir false asked for. */
    static RegionRenderer.View withoutAir(RegionRenderer.View view) {
        List<String> palette = new ArrayList<>();
        int[] remap = new int[view.palette().size()];

        for (int entry = 0; entry < view.palette().size(); entry++) {
            String block = view.palette().get(entry);

            if (RegionRenderer.isAir(block)) {
                remap[entry] = -1;
            } else {
                remap[entry] = palette.size();
                palette.add(block);
            }
        }

        List<RegionRenderer.View.Run> runs = new ArrayList<>();

        for (RegionRenderer.View.Run run : view.runs()) {
            if (run.block() >= 0 && run.block() < remap.length && remap[run.block()] >= 0) {
                runs.add(new RegionRenderer.View.Run(remap[run.block()], run.count()));
            }
        }
        return new RegionRenderer.View(view.from(), view.to(), view.size(), view.blocks(), palette, runs,
                view.missing(), view.outside());
    }

    /** The line that hands the id over, with what an id is good for. */
    static String kept(Snapshot kept, List<String> evicted, String how) {
        StringBuilder line = new StringBuilder("Kept as region ").append(kept.id());

        if (kept.name() != null) {
            line.append(" (\"").append(kept.name()).append("\")");
        }
        if (how != null) {
            line.append(", ").append(how);
        }
        line.append(". show-region draws any window of it, write-region puts it down again, and GET /regions/")
                .append(kept.id()).append(".schem on this server downloads it as a schematic.");
        if (!evicted.isEmpty()) {
            line.append(" Region(s) ").append(String.join(", ", evicted)).append(" were let go to make room.");
        }
        return line.toString();
    }

    private static String notes(List<String> notes) {
        return notes.isEmpty() ? "" : "\n" + String.join("\n", notes);
    }

    private static String origin(BotSession bot) {
        Messages.Status status = bot.status();

        return status == null || status.address() == null ? "read by " + bot.name() : status.address();
    }

    /**
     * Where the bot stands now, asked rather than read off its status.
     *
     * <p>A status is pushed every so often, and a walk that began a second after the bot was moved
     * read the place before, then faithfully put the bot back there. get-position is the bot's own
     * answer at the moment it is asked; the block it names is centred, as /tp would land there.
     */
    private Messages.Position position(BotSession bot) {
        ToolSpec getPosition = catalog.require("get-position");

        if (!bot.supports(getPosition.name())) {
            Messages.Status status = bot.status();

            return status == null ? null : status.position();
        }

        Messages.Result result = remote.fetch(getPosition, bot, Map.of()).result();

        if (!result.ok() || !(result.data() instanceof Map<?, ?> data) || !(data.get("position") instanceof Map<?, ?> at)
                || !(at.get("x") instanceof Number x) || !(at.get("y") instanceof Number y) || !(at.get("z") instanceof Number z)) {
            return null;
        }
        return new Messages.Position(x.intValue() + 0.5, y.doubleValue(), z.intValue() + 0.5);
    }

    private static String required(Map<String, Object> arguments, String name) {
        String value = ToolDispatcher.stringArg(arguments, name);

        if (value == null) {
            throw new IllegalArgumentException("\"%s\" is required".formatted(name));
        }
        return value;
    }
}
