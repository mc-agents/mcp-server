package kr.junhyung.mcagents.tool;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.junhyung.mcagents.bot.BotRegistry;
import kr.junhyung.mcagents.bot.BotSession;
import kr.junhyung.mcagents.bot.FeedEntry;
import kr.junhyung.mcagents.catalog.Catalog;
import kr.junhyung.mcagents.catalog.ToolSpec;
import org.springframework.stereotype.Component;

/**
 * Editing and measuring a box through WorldEdit.
 *
 * <p>Neither of these is a tool a bot has. A bot can run a command; WorldEdit is a plugin on the
 * server the bot is playing on. So what they compose is a short run of the bot's own
 * {@code run-command} calls -- the two selection commands and the one that does the work -- which
 * is the other half of what the orchestrate route means: the server driving a session rather than
 * forwarding one call.
 *
 * <p>WorldEdit answers out of band. The command is acknowledged at once and the words arrive as
 * chat whenever the edit gets there, seconds later for a large one, so the answer is collected from
 * the server's own lines on the chat feed rather than taken from what the command returned. Silence
 * is reported as silence: an edit that has not said it finished may still be running, and claiming
 * success for it would be the one failure worth avoiding here.
 */
@Component
public class RegionTools {

    /**
     * How long the two selection commands are given to be acknowledged.
     *
     * <p>Generous for something WorldEdit answers within a tick, because this is also the window
     * that decides the plugin is absent, and refusing a working edit on a busy server would be the
     * worse mistake of the two.
     */
    private static final int SELECTION_MS = 3_000;

    /** Between two looks at the chat feed. A tick is 50ms and nothing arrives faster than that. */
    private static final int POLL_MS = 200;

    /** {@link FeedEntry#source()} for a line the server produced; a player's line carries their name. */
    private static final String SYSTEM = "system";

    /* Paper answers "Unknown command. Type "/help" for help."; vanilla "Unknown or incomplete command". */
    private static final Pattern NO_SUCH_COMMAND =
            Pattern.compile("unknown (or incomplete )?command", Pattern.CASE_INSENSITIVE);

    /** What //distr writes a line of: a count, a share in brackets, and the block. */
    private static final Pattern DISTRIBUTION = Pattern.compile("^\\s*(\\d+)\\s+\\(([0-9.]+)%\\)\\s+(\\S+)\\s*$");

    private final BotRegistry bots;
    private final RemoteTools remote;
    private final Catalog catalog;

    public RegionTools(BotRegistry bots, RemoteTools remote, Catalog catalog) {
        this.bots = bots;
        this.remote = remote;
        this.catalog = catalog;
    }

    /** The arguments arrive bounded, because {@link Orchestration} holds the catalogue's limits for them. */
    public McpSchema.CallToolResult call(ToolSpec spec, Map<String, Object> arguments, Progress progress) {
        BotSession bot = bots.resolve(ToolDispatcher.stringArg(arguments, "bot"));
        ToolDispatcher.kindCheck(spec, bot);
        Region box = Region.of(spec.name(), arguments);

        /*
        Both tools take the bot for the whole run, not for each command. A player has one WorldEdit
        selection, so two of these interleaving their //pos1 and //pos2 would each measure or edit
        the other's box and both report the one they asked for. verify-region is read-only in the
        catalogue and so cannot be marked exclusive there -- that flag is about the world, and this
        is about the one piece of session state the two tools share.
        */
        if ("verify-region".equals(spec.name())) {
            return remote.exclusively(spec, bot, () -> verify(spec, bot, box, progress));
        }

        /* Composed first: a command that cannot be made is not worth claiming the bot for. */
        String operation = operationCommand(arguments);

        return remote.exclusively(spec, bot, () -> build(spec, bot, box, operation, progress));
    }

    /**
     * The one command an operation becomes, or a refusal before the bot is touched.
     *
     * <p>Which arguments each operation takes is WorldEdit's business and not ours: //set needs a
     * pattern and //naturalize takes none. Sending "//naturalize stone" has WorldEdit answer with
     * its usage line, which reaches an agent as a command that did nothing and said why in a
     * sentence about syntax it never wrote.
     */
    static String operationCommand(Map<String, Object> arguments) {
        String operation = ToolDispatcher.stringArg(arguments, "operation");
        String pattern = word(arguments, "pattern");
        String mask = word(arguments, "mask");

        if (operation == null) {
            throw new IllegalArgumentException("\"build-region\" needs \"operation\", and it was not given");
        }
        if (mask != null && !"replace".equals(operation)) {
            throw new IllegalArgumentException(
                    "only \"replace\" takes a mask, and the operation is \"%s\"".formatted(operation));
        }

        return switch (operation) {
            case "set", "walls", "faces", "overlay" -> "//%s %s".formatted(operation, needed(operation, pattern));
            case "replace" -> mask == null
                    ? "//replace %s".formatted(needed(operation, pattern))
                    : "//replace %s %s".formatted(mask, needed(operation, pattern));
            /* //hollow reads its first argument as a thickness, so a pattern has to follow one. */
            case "hollow" -> pattern == null ? "//hollow" : "//hollow 0 %s".formatted(pattern);
            case "smooth", "naturalize" -> withoutPattern(operation, pattern);
            default -> throw new IllegalArgumentException(
                    "\"%s\" is not an operation build-region runs".formatted(operation));
        };
    }

    /**
     * A pattern or a mask as one argument of a command.
     *
     * <p>Only what would break the command apart is judged. A space reaches WorldEdit as a second
     * argument -- "50% stone" becomes a pattern of "50%" and a stray "stone" -- a leading slash as
     * a command of its own, and a leading dash as one of the flags the operation itself takes:
     * //replace composes the mask first, so a mask of "-f" sits exactly where a flag goes. What
     * makes a pattern valid is WorldEdit's own grammar, which the server's plugins extend, so
     * guessing at it here would refuse patterns that work.
     */
    private static String word(Map<String, Object> arguments, String name) {
        String value = ToolDispatcher.stringArg(arguments, name);

        if (value == null) {
            return null;
        }
        if (value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("\"build-region\" %s is \"%s\", and a %s is one argument: it may not hold a space"
                    .formatted(name, value, name));
        }
        if (value.startsWith("/")) {
            throw new IllegalArgumentException("\"build-region\" %s is \"%s\", which would reach the server as a command of its own"
                    .formatted(name, value));
        }
        if (value.startsWith("-")) {
            throw new IllegalArgumentException("\"build-region\" %s is \"%s\", which would reach WorldEdit as a flag rather than as a %s"
                    .formatted(name, value, name));
        }
        return value;
    }

    private static String needed(String operation, String pattern) {
        if (pattern == null) {
            throw new IllegalArgumentException(
                    "\"%s\" needs a pattern, for example stone or 50%%stone,50%%cobblestone".formatted(operation));
        }
        return pattern;
    }

    private static String withoutPattern(String operation, String pattern) {
        if (pattern != null) {
            throw new IllegalArgumentException(
                    "\"%s\" takes no pattern, and \"%s\" was given as one".formatted(operation, pattern));
        }
        return "//" + operation;
    }

    private McpSchema.CallToolResult build(ToolSpec spec, BotSession bot, Region box, String operation,
            Progress progress) {
        long deadline = System.currentTimeMillis() + spec.defaultDeadlineMs();
        McpSchema.CallToolResult refused = select(spec, bot, box);

        if (refused != null) {
            return refused;
        }

        long said = bot.feed("chat").nextSeq();
        McpSchema.CallToolResult sent = send(bot, operation);

        if (sent != null) {
            return sent;
        }

        List<FeedEntry> replies = awaitChat(bot, said, deadline, progress, operation);

        if (replies.isEmpty()) {
            return ToolDispatcher.text(
                    "Ran %s over %s. WorldEdit said nothing in the %dms after it, so the edit may still be running rather than having finished -- a large one carries on well after the command is acknowledged. verify-region says what is in the box now."
                            .formatted(operation, box, spec.defaultDeadlineMs()));
        }
        return ToolDispatcher.text("Ran %s over %s. WorldEdit replied %s:\n%s"
                .formatted(operation, box, Trust.NOTICE, lines(replies)));
    }

    private McpSchema.CallToolResult verify(ToolSpec spec, BotSession bot, Region box, Progress progress) {
        long deadline = System.currentTimeMillis() + spec.defaultDeadlineMs();
        McpSchema.CallToolResult refused = select(spec, bot, box);

        if (refused != null) {
            return refused;
        }

        long said = bot.feed("chat").nextSeq();

        for (String command : List.of("//size", "//distr")) {
            McpSchema.CallToolResult sent = send(bot, command);

            if (sent != null) {
                return sent;
            }
        }

        List<FeedEntry> replies = awaitChat(bot, said, deadline, progress, "//size and //distr");

        if (replies.isEmpty()) {
            return ToolDispatcher.failure(
                    "WorldEdit answered neither //size nor //distr over %s within %dms. The selection was set, so run verify-region again."
                            .formatted(box, spec.defaultDeadlineMs()));
        }
        return ToolDispatcher.text("%s, as WorldEdit reports it %s:\n%s"
                .formatted(box, Trust.NOTICE, table(replies)));
    }

    /**
     * Set the selection, and decide here -- once, for both tools -- whether the plugin is there at
     * all. An agent told "Unknown command" by a raw chat line has to know that WorldEdit is what
     * answers //pos1 before that line means anything.
     *
     * @return the failure to answer with, or null when the selection was taken
     */
    private McpSchema.CallToolResult select(ToolSpec spec, BotSession bot, Region box) {
        long from = bot.feed("chat").nextSeq();

        for (String command : List.of("//pos1 " + box.lowerCorner(), "//pos2 " + box.upperCorner())) {
            McpSchema.CallToolResult sent = send(bot, command);

            if (sent != null) {
                return sent;
            }
        }

        List<FeedEntry> acknowledged = awaitChat(bot, from,
                System.currentTimeMillis() + SELECTION_MS, Progress.NONE, "the selection");

        if (acknowledged.isEmpty()) {
            return ToolDispatcher.failure(absent(spec,
                    "neither //pos1 nor //pos2 was answered within %dms. WorldEdit acknowledges a selection at once, so it is most likely not installed."
                            .formatted(SELECTION_MS)));
        }

        FeedEntry unknown = acknowledged.stream().filter(line -> NO_SUCH_COMMAND.matcher(line.rendered()).find())
                .findFirst().orElse(null);

        if (unknown != null) {
            return ToolDispatcher.failure(Trust.mark(absent(spec,
                    "//pos1 came back as \"%s\"".formatted(unknown.rendered()))));
        }
        return null;
    }

    /** The one answer both tools give when the plugin is not there. */
    private static String absent(ToolSpec spec, String why) {
        return "%s needs WorldEdit or FastAsyncWorldEdit on the server, and %s\nread-region reads the same box out of the bot's own client and needs no plugin at all, which is what to use instead."
                .formatted(spec.name(), why);
    }

    /**
     * One command, through the tool the bot already has.
     *
     * <p>{@code call} and not {@code compose}: compose collects the chat itself over a window of
     * its own, which would both cut this collection short and hand back a sentence to take apart
     * again. The chat is read here instead, from one mark taken before the first command.
     *
     * @return the bot's own refusal, which is the actionable one and is not rewrapped, or null
     */
    private McpSchema.CallToolResult send(BotSession bot, String command) {
        ToolSpec runCommand = catalog.require("run-command");

        ToolDispatcher.offerCheck(runCommand, bot);

        McpSchema.CallToolResult sent = remote.call(runCommand, bot, Map.of("command", command));

        return Boolean.TRUE.equals(sent.isError()) ? sent : null;
    }

    /**
     * What the server said after a mark, once it has stopped saying it.
     *
     * <p>Returning the first line would cut every multi-line answer in half: //size is six lines
     * and //distr one per kind of block. So the feed is watched until a poll adds nothing, which
     * is also what lets a //set that takes ten seconds be waited out without a fixed guess at how
     * long an edit takes.
     */
    private static List<FeedEntry> awaitChat(BotSession bot, long since, long deadline, Progress progress,
            String waitingFor) {
        long started = System.currentTimeMillis();
        int seen = 0;

        while (System.currentTimeMillis() < deadline) {
            List<FeedEntry> lines = systemLines(bot, since);

            if (!lines.isEmpty() && lines.size() == seen) {
                return lines;
            }
            seen = lines.size();
            progress.report("waiting for WorldEdit to answer %s (%d line(s) so far)".formatted(waitingFor, seen),
                    System.currentTimeMillis() - started, deadline - started);
            sleep(POLL_MS);
        }
        return systemLines(bot, since);
    }

    /**
     * What the server itself said after a mark, which is the only thing either tool decides from.
     *
     * <p>WorldEdit answers as the server and those lines are marked {@code system}; a line a player
     * typed carries that player's name instead. Reading the feed whole handed a player both
     * decisions made here: a chat line arriving inside the selection window passes for the
     * acknowledgement that says the plugin is installed, and one arriving during an edit is
     * collected and shown as WorldEdit's own answer. Whoever is on the server also decides when a
     * poll stops adding lines, so an edit could be waited out by someone talking through it.
     */
    private static List<FeedEntry> systemLines(BotSession bot, long since) {
        return bot.feed("chat").since(since).stream().filter(line -> SYSTEM.equals(line.source())).toList();
    }

    /**
     * //distr as columns, with everything else above it as it arrived.
     *
     * <p>Only the shape //distr writes is taken apart, and a line that is not that shape is kept
     * whole: //size writes six of those, a plugin may write its own, and a table that swallowed a
     * line it could not parse would be a table that hid the one saying why the answer is empty.
     */
    private static String table(List<FeedEntry> replies) {
        List<String> other = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();

        for (FeedEntry line : replies) {
            Matcher row = DISTRIBUTION.matcher(line.rendered());

            if (row.matches()) {
                rows.add(new String[] {row.group(3), row.group(1), row.group(2) + "%"});
            } else {
                other.add("  " + line.rendered());
            }
        }
        if (rows.isEmpty()) {
            return String.join("\n", other);
        }

        rows.addFirst(new String[] {"Block", "Count", "Share"});
        String format = "  %%-%ds  %%%ds  %%%ds".formatted(width(rows, 0), width(rows, 1), width(rows, 2));
        List<String> out = new ArrayList<>(other);

        rows.forEach(row -> out.add(format.formatted((Object[]) row)));

        return String.join("\n", out);
    }

    private static int width(List<String[]> rows, int column) {
        return rows.stream().mapToInt(row -> row[column].length()).max().orElse(1);
    }

    private static String lines(List<FeedEntry> replies) {
        return replies.stream().map(line -> "  " + line.rendered()).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    private static void sleep(int millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
