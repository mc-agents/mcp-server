package kr.junhyung.mcagents.tool;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import kr.junhyung.mcagents.bot.BotSession;
import kr.junhyung.mcagents.bot.FeedEntry;
import kr.junhyung.mcagents.catalog.Catalog;
import kr.junhyung.mcagents.catalog.ToolSpec;
import org.springframework.stereotype.Component;

/**
 * The bot's own run-command, and what the server said after it.
 *
 * <p>Every tool the server composes out of commands -- an edit through WorldEdit, a survey that
 * teleports between tiles, a copy put down with /fill -- sends the same way and listens the same
 * way, so the sending and the listening live here once. A command's answer is not its return
 * value but what the server writes to chat afterwards, and only the server's own lines count:
 * a player's line carries their name as its source, and reading the feed whole handed a player
 * every decision made from it.
 */
@Component
public class Commands {

    /** Between two looks at the chat feed. A tick is 50ms and nothing arrives faster than that. */
    static final int POLL_MS = 200;

    /**
     * How long the server has to have said nothing before its answer counts as over.
     *
     * <p>One poll was not it: FastAsyncWorldEdit answers //pos1 on the tick and //distr from a
     * thread of its own some hundreds of milliseconds later, and verify-region came back with the
     * acknowledgement alone and none of the measurement.
     */
    static final int QUIET_MS = 1_000;

    /** {@link FeedEntry#source()} for a line the server produced; a player's line carries their name. */
    static final String SYSTEM = "system";

    /* Paper answers "Unknown command. Type "/help" for help."; vanilla "Unknown or incomplete command". */
    static final Pattern NO_SUCH_COMMAND =
            Pattern.compile("unknown (or incomplete )?command", Pattern.CASE_INSENSITIVE);

    /** What the game says to a player without the permission level a command needs. */
    static final Pattern NO_PERMISSION = Pattern.compile("(do not have permission|not allowed|insufficient)", Pattern.CASE_INSENSITIVE);

    private final RemoteTools remote;
    private final Catalog catalog;

    public Commands(RemoteTools remote, Catalog catalog) {
        this.remote = remote;
        this.catalog = catalog;
    }

    /**
     * One command, through the tool the bot already has.
     *
     * <p>{@code call} and not {@code compose}: compose collects the chat itself over a window of
     * its own, which would both cut a caller's own collection short and hand back a sentence to
     * take apart again. The chat is read by the caller instead, from a mark taken before the first
     * command.
     *
     * @return the bot's own refusal, which is the actionable one and is not rewrapped, or null
     */
    McpSchema.CallToolResult send(BotSession bot, String command) {
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
     * long an edit takes. A poll adding nothing is not the end: the feed has to have been quiet
     * for {@link #QUIET_MS}, since a plugin that answers from its own thread pauses between lines.
     */
    static List<FeedEntry> awaitChat(BotSession bot, long since, long deadline, Progress progress,
            String waitingFor) {
        long started = System.currentTimeMillis();
        long lastNew = started;
        int seen = 0;

        while (System.currentTimeMillis() < deadline) {
            List<FeedEntry> lines = systemLines(bot, since);

            if (lines.size() != seen) {
                seen = lines.size();
                lastNew = System.currentTimeMillis();
            } else if (seen > 0 && System.currentTimeMillis() - lastNew >= QUIET_MS) {
                return lines;
            }
            progress.report("waiting for the server to answer %s (%d line(s) so far)".formatted(waitingFor, seen),
                    System.currentTimeMillis() - started, deadline - started);
            sleep(POLL_MS);
        }
        return systemLines(bot, since);
    }

    /**
     * The first line after a mark that says one thing, or null when the deadline passes first.
     *
     * <p>For a command whose next command must not go out until this one has run: FastAsyncWorldEdit
     * handles commands on threads of its own, and //pos1, //pos2 and //set sent within a tick ran
     * in whatever order the threads got to them, with the edit taking the corners of the box before.
     * Its acknowledgement is the one thing that says a command has run.
     */
    static FeedEntry awaitLine(BotSession bot, long since, long deadline, Pattern said) {
        while (true) {
            for (FeedEntry line : systemLines(bot, since)) {
                if (said.matcher(line.rendered()).find()) {
                    return line;
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                return null;
            }
            sleep(POLL_MS / 4);
        }
    }

    /** What the server itself said after a mark, which is the only thing a composed tool decides from. */
    static List<FeedEntry> systemLines(BotSession bot, long since) {
        return bot.feed("chat").since(since).stream().filter(line -> SYSTEM.equals(line.source())).toList();
    }

    /** The line that says the command does not exist for this player, or null. */
    static FeedEntry unknownCommand(List<FeedEntry> lines) {
        return lines.stream().filter(line -> NO_SUCH_COMMAND.matcher(line.rendered()).find()).findFirst().orElse(null);
    }

    static FeedEntry refused(List<FeedEntry> lines) {
        return lines.stream()
                .filter(line -> NO_SUCH_COMMAND.matcher(line.rendered()).find()
                        || NO_PERMISSION.matcher(line.rendered()).find())
                .findFirst().orElse(null);
    }

    static void sleep(int millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
