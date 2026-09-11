package dev.mcagents.mcp.tool;

import dev.mcagents.mcp.bot.BotRegistry;
import dev.mcagents.mcp.bot.BotSession;
import dev.mcagents.mcp.bot.FeedEntry;
import dev.mcagents.mcp.bot.WaitOutcome;
import dev.mcagents.mcp.catalog.ToolSpec;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Component;

/**
 * Tools the server answers alone.
 *
 * <p>Reading a feed does not involve the bot that filled it, which is what lets {@code read-chat}
 * still work after that bot has been kicked — and the lines just before a kick are usually the
 * ones that say why.
 */
@Component
public class LocalTools {

    /** Which feed each reading tool draws from. */
    private static final Map<String, String> FEED_OF = Map.ofEntries(
            Map.entry("read-chat", "chat"),
            Map.entry("wait-for-chat", "chat"),
            Map.entry("read-action-bar", "actionBar"),
            Map.entry("wait-for-action-bar", "actionBar"),
            Map.entry("read-title", "title"),
            Map.entry("wait-for-title", "title"),
            Map.entry("read-dialog", "dialog"),
            Map.entry("wait-for-dialog", "dialog"),
            Map.entry("read-effects", "effect"),
            Map.entry("wait-for-effect", "effect"));

    private final BotRegistry bots;

    public LocalTools(BotRegistry bots) {
        this.bots = bots;
    }

    public McpSchema.CallToolResult call(ToolSpec spec, Map<String, Object> arguments) {
        String feed = FEED_OF.get(spec.name());

        if (feed != null) {
            return spec.name().startsWith("wait-")
                    ? await(spec, feed, arguments)
                    : read(spec, feed, arguments);
        }
        return switch (spec.name()) {
            case "list-bots" -> listBots();
            case "get-bot-status", "detect-gamemode" -> ToolDispatcher.failure(
                    "%s is not wired up yet".formatted(spec.name()));
            default -> ToolDispatcher.failure("%s is not wired up yet".formatted(spec.name()));
        };
    }

    public McpSchema.CallToolResult orchestrate(ToolSpec spec, Map<String, Object> arguments) {
        return ToolDispatcher.failure(
                "%s needs the operator, which is not wired up yet".formatted(spec.name()));
    }

    private McpSchema.CallToolResult read(ToolSpec spec, String feed, Map<String, Object> arguments) {
        BotSession bot = bots.resolve(ToolDispatcher.stringArg(arguments, "bot"));
        int count = intArg(arguments, "count", 5);

        List<FeedEntry> lines = bot.feed(feed).recent(count);

        if (lines.isEmpty()) {
            return ToolDispatcher.text("The server has not sent %s yet.".formatted(nounOf(feed)));
        }

        boolean manySources = lines.stream().map(FeedEntry::source).distinct().count() > 1;
        String body = lines.stream().map(line -> describe(line, manySources))
                .reduce((a, b) -> a + "\n" + b).orElse("");

        return ToolDispatcher.text(spec.untrusted() ? Trust.mark(body) : body);
    }

    private McpSchema.CallToolResult await(ToolSpec spec, String feed, Map<String, Object> arguments) {
        BotSession bot = bots.resolve(ToolDispatcher.stringArg(arguments, "bot"));
        String source = ToolDispatcher.stringArg(arguments, "pattern");
        int timeoutMs = intArg(arguments, "timeoutMs", 10_000);

        Pattern pattern;
        try {
            pattern = Pattern.compile(source == null ? "" : source);
        } catch (PatternSyntaxException e) {
            return ToolDispatcher.failure(
                    "\"%s\" is not a valid regular expression: %s".formatted(source, e.getDescription()));
        }

        FeedEntry showing = bot.feed(feed).latest();
        if (showing != null && pattern.matcher(showing.text()).find()) {
            return ToolDispatcher.text(Trust.mark(
                    "The %s already shows: %s".formatted(nounOf(feed), showing.text())));
        }

        try {
            WaitOutcome outcome = bot.waitOn(feed, line -> pattern.matcher(line.text()).find(), timeoutMs)
                    .get(timeoutMs + 2_000L, TimeUnit.MILLISECONDS);

            return switch (outcome) {
                case WaitOutcome.Matched matched -> ToolDispatcher.text(Trust.mark(
                        "Matched: (%s) %s".formatted(matched.entry().source(), matched.entry().text())));

                case WaitOutcome.TimedOut timedOut -> ToolDispatcher.failure(
                        "no %s matched /%s/ within %dms.".formatted(nounOf(feed), source, timedOut.waitedMs()));

                /*
                Being kicked after four seconds and matching nothing in ten are different facts.
                Reporting the first as the second sends you looking in the wrong place.
                */
                case WaitOutcome.Abandoned abandoned -> ToolDispatcher.failure(
                        "bot \"%s\" stopped while waiting for %s matching /%s/ (%s). %dms elapsed of %dms."
                                .formatted(bot.name(), nounOf(feed), source, abandoned.reason(),
                                        abandoned.waitedMs(), timeoutMs));
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolDispatcher.failure("the wait was interrupted");
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            return ToolDispatcher.failure("the wait did not settle: %s".formatted(e));
        }
    }

    private McpSchema.CallToolResult listBots() {
        List<BotSession> all = bots.all();

        if (all.isEmpty()) {
            return ToolDispatcher.text("No bots are connected. Call join-server to add one.");
        }
        String body = all.stream()
                .map(bot -> "  %s (%s): %s".formatted(bot.name(), bot.kind(),
                        bot.status() == null ? "linked, not in a world" : bot.status().state()))
                .reduce((a, b) -> a + "\n" + b).orElse("");

        return ToolDispatcher.text("%d bot(s):\n%s".formatted(all.size(), body));
    }

    private static String describe(FeedEntry line, boolean withSource) {
        String repeats = line.repeats() > 1
                ? " (shown %d times, first at %s)".formatted(line.repeats(), Instant.ofEpochMilli(line.firstSeen()))
                : "";
        String source = withSource ? line.source() + ": " : "";
        return "[%s] %s%s%s".formatted(Instant.ofEpochMilli(line.seen()), source, line.text(), repeats);
    }

    private static String nounOf(String feed) {
        return switch (feed) {
            case "chat" -> "a chat line";
            case "actionBar" -> "an action bar";
            case "title" -> "a title";
            case "dialog" -> "a dialog";
            default -> "a sound or particle";
        };
    }

    private static int intArg(Map<String, Object> arguments, String name, int fallback) {
        Object value = arguments == null ? null : arguments.get(name);
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
