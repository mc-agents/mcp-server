package kr.junhyung.mcagents.tool;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
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
 * What a server's custom blocks look like, learned once and kept.
 *
 * <p>CraftEngine gives a custom block the look of a vanilla state nothing else uses -- a note block
 * with some instrument and note, a tripwire, a leaf -- and that look is all a client ever receives.
 * So a bot reads {@code note_block[instrument=banjo,note=3,powered=false]} where the server holds
 * {@code 2025summer:bar_table[facing=east]}, and nothing on the client can say which is which.
 * Asking the server a block at a time is a command a block, which no region can afford; asking it
 * once for every custom block it has is a minute, and after that the answer is a lookup.
 *
 * <p>The dictionary is learned by placing. The server's tab completion lists every custom block
 * state by name; each is put down with CraftEngine's own {@code debug setblock} in a scratch row
 * at the top of the world, the row is read back, and what the client saw is what that name looks
 * like. The internal id WorldEdit files it under ({@code craftengine:custom_N}) is asked for as
 * well, so a schematic can carry the block the way FastAsyncWorldEdit would write it. Kept per
 * server address for the life of this process; a plugin reload that reassigns looks means learning
 * again.
 */
@Component
public class CustomBlocks {

    /** The one namespace a client's own block registry answers for; anything else is a custom block. */
    static final String VANILLA = "minecraft:";

    /** How many blocks one scratch row holds: what one read-region takes on an axis. */
    static final int ROW = Region.MAX_SPAN;

    /** Where the scratch row goes unless told otherwise: the overworld's top layer. */
    static final int SCRATCH_Y = 319;

    /** The most completions one call asks for, which is the catalogue's own limit for it. */
    private static final int COMPLETIONS = 500;

    /** What tab completion after it lists: every custom block state, by name. */
    private static final String NAMES_PREFIX = "/craftengine debug get-block-internal-id ";

    private static final Pattern INTERNAL_ID = Pattern.compile("craftengine:custom_\\d+");

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** A custom block state: what the server calls it, what a client sees, and what WorldEdit files it as. */
    public record Entry(String id, String appearance, String internal) {}

    /** Everything learned about one server. */
    public static final class Dictionary {

        private final Map<String, Entry> byAppearance = new LinkedHashMap<>();
        private final Map<String, Entry> byId = new LinkedHashMap<>();
        private final Map<String, Entry> byInternal = new LinkedHashMap<>();

        void add(Entry entry) {
            byId.put(entry.id(), entry);
            if (entry.appearance() != null) {
                byAppearance.putIfAbsent(entry.appearance(), entry);
            }
            if (entry.internal() != null) {
                byInternal.put(entry.internal(), entry);
            }
        }

        /** The custom block a client-side state stands for, or null when it is what it looks like. */
        public Entry byAppearance(String state) {
            return byAppearance.get(state);
        }

        public Entry byId(String id) {
            return byId.get(id);
        }

        public Entry byInternal(String internal) {
            return byInternal.get(internal);
        }

        public int size() {
            return byId.size();
        }
    }

    private final Map<String, Dictionary> known = new ConcurrentHashMap<>();
    private final RemoteTools remote;
    private final Commands commands;
    private final Catalog catalog;

    public CustomBlocks(RemoteTools remote, Commands commands, Catalog catalog) {
        this.remote = remote;
        this.commands = commands;
        this.catalog = catalog;
    }

    /** What is known about the server a bot is on, or null when nothing has been learned there. */
    public Dictionary of(BotSession bot) {
        return of(address(bot));
    }

    public Dictionary of(String address) {
        return address == null ? null : known.get(address);
    }

    /** A palette with every look a custom block wears replaced by the custom block's own name. */
    List<String> translate(BotSession bot, List<String> palette) {
        Dictionary dictionary = of(bot);

        if (dictionary == null) {
            return palette;
        }

        List<String> named = new ArrayList<>(palette.size());

        for (String state : palette) {
            Entry custom = dictionary.byAppearance(state);
            named.add(custom == null ? state : custom.id());
        }
        return named;
    }

    static boolean isCustom(String block) {
        int colon = block.indexOf(':');

        return colon > 0 && !block.startsWith(VANILLA);
    }

    /** learn-custom-blocks: the whole dictionary for the server the bot is on. */
    public McpSchema.CallToolResult learn(ToolSpec spec, BotSession bot, Map<String, Object> arguments, Progress progress) {
        String address = address(bot);

        if (address == null) {
            return ToolDispatcher.failure("bot \"%s\" is not on a server.".formatted(bot.name()));
        }
        Region scratch = arguments.get("scratch") instanceof Map<?, ?> corner
                ? Region.corners(spec.name(), Map.of("from", corner, "to", corner))
                : null;

        return remote.exclusively(spec, bot, () -> learned(spec, bot, address, scratch, progress));
    }

    private McpSchema.CallToolResult learned(ToolSpec spec, BotSession bot, String address, Region scratch, Progress progress) {
        long started = System.currentTimeMillis();
        long deadline = started + spec.defaultDeadlineMs();
        List<String> names;

        try {
            names = names(bot, "");
        } catch (IllegalStateException nothing) {
            return ToolDispatcher.failure(nothing.getMessage());
        }
        if (names.isEmpty()) {
            return ToolDispatcher.failure(
                    "the server completes nothing after \"%s\", so either CraftEngine is not on it or the bot may not run its debug commands (permission ce.command.debug.*, which op has)."
                            .formatted(NAMES_PREFIX.trim()));
        }

        /* One entry a state: the bare name is the default state, listed again with its properties. */
        List<String> states = states(names);
        Dictionary dictionary = new Dictionary();
        Messages.Position stood = position(bot);

        if (scratch == null && stood == null) {
            return ToolDispatcher.failure("bot \"%s\" did not say where it stands, and the scratch row is placed beside it.".formatted(bot.name()));
        }

        /* Beside the bot so the chunks are held, and at the top of the world where nothing is. */
        int rowY = scratch == null ? SCRATCH_Y : scratch.minY();
        int rowZ = scratch == null ? (int) Math.floor(stood.z()) : scratch.minZ();
        int rowX = scratch == null ? (int) Math.floor(stood.x()) - ROW / 2 : scratch.minX();
        int placed = 0;
        int unseen = 0;
        List<String> notes = new ArrayList<>();

        try {
            for (int batch = 0; batch < states.size(); batch += ROW) {
                if (System.currentTimeMillis() > deadline) {
                    notes.add("Stopped after %d of %d states: the call's deadline is up.".formatted(batch, states.size()));
                    break;
                }
                List<String> row = states.subList(batch, Math.min(batch + ROW, states.size()));

                progress.report("placing custom blocks %d-%d of %d".formatted(batch + 1, batch + row.size(), states.size()),
                        System.currentTimeMillis() - started, spec.defaultDeadlineMs());

                for (int i = 0; i < row.size(); i++) {
                    McpSchema.CallToolResult sent = commands.send(bot,
                            "craftengine debug setblock %d %d %d %s".formatted(rowX + i, rowY, rowZ, row.get(i)));
                    if (sent != null) {
                        return sent;
                    }
                }

                Region box = new Region(rowX, rowY, rowZ, rowX + row.size() - 1, rowY, rowZ);
                RegionRenderer.View view = settled(bot, box);

                if (view == null) {
                    notes.add("The scratch row at %s never reached the client whole, so states %d-%d were not learned."
                            .formatted(Text.block(rowX, rowY, rowZ), batch + 1, batch + row.size()));
                    continue;
                }

                List<String> seen = spelled(view);
                List<String> internals = internals(bot, row);

                for (int i = 0; i < row.size(); i++) {
                    String appearance = seen.get(i);
                    String internal = internals.get(i);

                    if (appearance == null || RegionRenderer.isAir(appearance)) {
                        unseen++;
                        dictionary.add(new Entry(row.get(i), null, internal));
                    } else {
                        placed++;
                        dictionary.add(new Entry(row.get(i), appearance, internal));
                    }
                }
            }
        } finally {
            commands.send(bot, "fill %d %d %d %d %d %d air".formatted(rowX, rowY, rowZ, rowX + ROW - 1, rowY, rowZ));
        }

        known.put(address, dictionary);

        List<String> out = new ArrayList<>();

        out.add("Learned %d custom block state(s) on %s in %ds: %d with the look a client sees them as, %d that placed as nothing (a block that needs support, or a furniture)."
                .formatted(dictionary.size(), address, (System.currentTimeMillis() - started) / 1_000, placed, unseen));
        out.add("read-region on this server now names them, write-region puts them down through WorldEdit, and a schematic carries them as WorldEdit files them. Learn again after the plugin is reloaded.");
        out.addAll(notes);

        return ToolDispatcher.text(String.join("\n", out));
    }

    /**
     * Every name the server completes after the prefix, paged by adding a character when there
     * are more than one call shows: the completion says how many there are in all.
     */
    private List<String> names(BotSession bot, String typed) {
        ToolSpec complete = catalog.require("complete-command");

        ToolDispatcher.offerCheck(complete, bot);

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("text", NAMES_PREFIX + typed);
        arguments.put("limit", COMPLETIONS);

        Messages.Result result;

        try {
            result = remote.fetch(complete, bot, arguments).result();
        } catch (IllegalStateException unanswered) {
            /* A server without the command answers no suggestions at all, which is the same nothing. */
            return List.of();
        }
        if (!result.ok() || !(result.data() instanceof Map<?, ?> data)) {
            return List.of();
        }

        int total = data.get("total") instanceof Number number ? number.intValue() : 0;
        List<String> names = new ArrayList<>();

        if (data.get("completions") instanceof List<?> completions) {
            for (Object completion : completions) {
                if (completion instanceof Map<?, ?> one && one.get("name") instanceof String name) {
                    names.add(name);
                }
            }
        }
        if (total <= COMPLETIONS || typed.length() > 40) {
            return names;
        }

        /* Too many for one call: narrow by the next character, from what the first page showed. */
        List<String> paged = new ArrayList<>();
        java.util.Set<String> prefixes = new java.util.LinkedHashSet<>();

        for (String name : names) {
            if (name.length() > typed.length()) {
                prefixes.add(name.substring(0, typed.length() + 1));
            }
        }
        for (String prefix : prefixes) {
            for (String name : names(bot, prefix)) {
                if (!paged.contains(name)) {
                    paged.add(name);
                }
            }
        }
        return paged;
    }

    /** The states among the names: those with properties, and a bare name only when it has no variants. */
    static List<String> states(List<String> names) {
        java.util.Set<String> withVariants = new java.util.HashSet<>();

        for (String name : names) {
            int bracket = name.indexOf('[');
            if (bracket > 0) {
                withVariants.add(name.substring(0, bracket));
            }
        }

        List<String> states = new ArrayList<>();

        for (String name : names) {
            if (name.indexOf('[') > 0 || !withVariants.contains(name)) {
                states.add(name);
            }
        }
        return states;
    }

    /** The row read back once the client has caught up with the placing, or null when it never does. */
    private RegionRenderer.View settled(BotSession bot, Region box) {
        long deadline = System.currentTimeMillis() + RegionSurvey.SETTLE_MS;

        while (true) {
            RegionRenderer.View view = read(bot, box);

            if (view.missing() == 0 && view.outside() == 0) {
                List<String> seen = spelled(view);
                if (seen.stream().noneMatch(state -> state == null || RegionRenderer.isAir(state))
                        || System.currentTimeMillis() >= deadline) {
                    return view;
                }
            } else if (System.currentTimeMillis() >= deadline) {
                return null;
            }
            Commands.sleep(Commands.POLL_MS);
        }
    }

    private RegionRenderer.View read(BotSession bot, Region box) {
        ToolSpec readRegion = catalog.require("read-region");

        ToolDispatcher.offerCheck(readRegion, bot);

        Map<String, Object> arguments = new LinkedHashMap<>(box.corners());

        arguments.put("includeAir", true);

        Messages.Result result = remote.fetch(readRegion, bot, arguments).result();

        if (!result.ok() || result.data() == null) {
            throw new IllegalStateException("read-region over the scratch row failed: " + result.text());
        }
        return MAPPER.convertValue(result.data(), RegionRenderer.View.class);
    }

    /** The row's blocks along x, one name each, as the runs spell them. */
    private static List<String> spelled(RegionRenderer.View view) {
        List<String> blocks = new ArrayList<>();

        for (RegionRenderer.View.Run run : view.runs()) {
            for (int i = 0; i < run.count(); i++) {
                blocks.add(run.block() >= 0 && run.block() < view.palette().size() ? view.palette().get(run.block()) : null);
            }
        }
        while (blocks.size() < view.blocks()) {
            blocks.add(null);
        }
        return blocks;
    }

    /**
     * The ids WorldEdit files a row of custom blocks under, asked for back to back.
     *
     * <p>The server answers each in one line and in the order asked, since commands run one after
     * another on its main thread, so a row's answers are read off the feed as one block of lines
     * rather than waited for one at a time -- which at a second each was twenty minutes for a
     * server with a thousand states. A row whose answers do not add up is asked again one by one.
     */
    private List<String> internals(BotSession bot, List<String> row) {
        long mark = bot.feed("chat").nextSeq();

        for (String id : row) {
            if (commands.send(bot, "craftengine debug get-block-internal-id " + id) != null) {
                return oneByOne(bot, row);
            }
        }

        long deadline = System.currentTimeMillis() + 3_000;
        List<String> found;

        while (true) {
            found = new ArrayList<>();
            for (FeedEntry line : Commands.systemLines(bot, mark)) {
                Matcher id = INTERNAL_ID.matcher(line.rendered());
                if (id.find()) {
                    found.add(id.group());
                }
            }
            if (found.size() >= row.size() || System.currentTimeMillis() >= deadline) {
                break;
            }
            Commands.sleep(Commands.POLL_MS);
        }
        return found.size() == row.size() ? found : oneByOne(bot, row);
    }

    private List<String> oneByOne(BotSession bot, List<String> row) {
        List<String> found = new ArrayList<>();

        for (String id : row) {
            long mark = bot.feed("chat").nextSeq();
            String internal = null;

            if (commands.send(bot, "craftengine debug get-block-internal-id " + id) == null) {
                for (FeedEntry line : Commands.awaitChat(bot, mark, System.currentTimeMillis() + 2_000, Progress.NONE, "the internal id")) {
                    Matcher one = INTERNAL_ID.matcher(line.rendered());
                    if (one.find()) {
                        internal = one.group();
                        break;
                    }
                }
            }
            found.add(internal);
        }
        return found;
    }

    private Messages.Position position(BotSession bot) {
        ToolSpec getPosition = catalog.require("get-position");

        if (!bot.supports(getPosition.name())) {
            Messages.Status status = bot.status();
            return status == null ? null : status.position();
        }

        Messages.Result result = remote.fetch(getPosition, bot, Map.of()).result();

        if (result.ok() && result.data() instanceof Map<?, ?> data && data.get("position") instanceof Map<?, ?> at
                && at.get("x") instanceof Number x && at.get("y") instanceof Number y && at.get("z") instanceof Number z) {
            return new Messages.Position(x.doubleValue(), y.doubleValue(), z.doubleValue());
        }
        return null;
    }

    private static String address(BotSession bot) {
        Messages.Status status = bot.status();

        return status == null ? null : status.address();
    }
}
