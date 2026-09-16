package kr.junhyung.mcagents.tool;

import kr.junhyung.mcagents.bot.BotRegistry;
import kr.junhyung.mcagents.bot.BotSession;
import kr.junhyung.mcagents.bot.FeedEntry;
import kr.junhyung.mcagents.bot.WaitOutcome;
import kr.junhyung.mcagents.catalog.ToolSpec;
import kr.junhyung.mcagents.probe.ServerListPing;
import kr.junhyung.mcagents.protocol.Messages;
import kr.junhyung.mcagents.render.Text;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
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

    /** How long one attempt in wait-for-server may take, and how long it waits before the next. */
    private static final int POLL_TIMEOUT_MS = 2_000;

    private static final int POLL_INTERVAL_MS = 1_000;

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
            Map.entry("wait-for-effect", "effect"),
            Map.entry("read-toasts", "toast"),
            Map.entry("wait-for-toast", "toast"));

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
            case "get-bot-status" -> status(arguments);
            case "detect-gamemode" -> gameMode(arguments);
            case "ping-server" -> ping(arguments);
            case "wait-for-server" -> waitForServer(spec, arguments);
            default -> ToolDispatcher.failure("%s is not wired up yet".formatted(spec.name()));
        };
    }

    /**
     * Everything the server last heard, whether or not the bot is still there.
     *
     * <p>This is the tool every other failure message points at, so it answers from the cached
     * status rather than asking the bot: a bot that has stopped answering is precisely when the
     * question gets asked, and a call that hangs would be the least useful possible reply.
     */
    private McpSchema.CallToolResult status(Map<String, Object> arguments) {
        BotSession bot = bots.resolve(ToolDispatcher.stringArg(arguments, "bot"));
        Messages.Status last = bot.status();

        if (last == null) {
            return ToolDispatcher.text(
                    "Bot \"%s\" (kind: %s) is linked but has not joined a world. Call join-server to send it to one."
                            .formatted(bot.name(), bot.kind()));
        }

        StringBuilder body = new StringBuilder("Bot \"%s\" (kind: %s) is %s."
                .formatted(bot.name(), bot.kind(), last.state()));

        append(body, "Server", last.address());
        append(body, "Username", last.username());
        append(body, "Minecraft", last.mcVersion());
        append(body, "Brand", last.serverBrand());
        append(body, "Game mode", last.gameMode());
        append(body, "Dimension", last.dimension());

        if (last.position() != null) {
            append(body, "Position",
                    Text.block(last.position().x(), last.position().y(), last.position().z()));
        }
        if (last.health() != null) {
            append(body, "Health", "%.1f of 20".formatted(last.health()));
        }
        if (last.food() != null) {
            append(body, "Food", "%.1f of 20".formatted(last.food()));
        }
        if (Boolean.TRUE.equals(last.dead())) {
            append(body, "Dead", (last.causeOfDeath() == null ? "yes" : last.causeOfDeath())
                    + ". Every tool that acts in the world refuses until respawn is called.");
        }
        append(body, "Reason", last.reason());

        append(body, "Last error", last.lastError());
        append(body, "Last seen", Instant.ofEpochMilli(last.ts()).toString());

        return ToolDispatcher.text(body.toString());
    }

    private McpSchema.CallToolResult gameMode(Map<String, Object> arguments) {
        BotSession bot = bots.resolve(ToolDispatcher.stringArg(arguments, "bot"));
        Messages.Status last = bot.status();

        if (last == null || last.gameMode() == null) {
            return ToolDispatcher.failure(
                    "bot \"%s\" has not been told a game mode. It is %s; get-bot-status says more."
                            .formatted(bot.name(), last == null ? "not in a world" : last.state()));
        }
        return ToolDispatcher.text("Bot \"%s\" is in %s mode.".formatted(bot.name(), last.gameMode()));
    }

    private McpSchema.CallToolResult ping(Map<String, Object> arguments) {
        String host = ToolDispatcher.stringArg(arguments, "host");
        int port = intArg(arguments, "port", 25_565);
        int timeoutMs = intArg(arguments, "timeoutMs", 5_000);

        try {
            ServerListPing.Pong pong = ServerListPing.ping(host, port, timeoutMs);

            return ToolDispatcher.text(Trust.mark("""
                    %s:%d answered in %dms.
                      Version: %s (protocol %d)
                      Players: %d of %d
                      MOTD: %s"""
                    .formatted(host, port, pong.latencyMs(), pong.version(), pong.protocol(),
                            pong.online(), pong.max(), pong.motd())));
        } catch (IOException e) {
            return ToolDispatcher.failure(
                    "%s:%d did not answer a server list ping within %dms (%s). The server may be down, still starting, or unreachable from here."
                            .formatted(host, port, timeoutMs, e.getMessage()));
        }
    }

    /**
     * Block until a server is up, so a redeploy and the join that follows it do not race.
     *
     * <p>Polling is the whole implementation on purpose. A server that is starting refuses
     * connections outright, and there is nothing to subscribe to; the interval is short enough that
     * the wait is not what makes the development loop slow.
     */
    private McpSchema.CallToolResult waitForServer(ToolSpec spec, Map<String, Object> arguments) {
        String host = ToolDispatcher.stringArg(arguments, "host");
        int port = intArg(arguments, "port", 25_565);
        int timeoutMs = intArg(arguments, "timeoutMs", spec.defaultDeadlineMs());

        long deadline = System.currentTimeMillis() + timeoutMs;
        int attempts = 0;
        String lastFailure = "it was never reachable";

        while (System.currentTimeMillis() < deadline) {
            attempts++;
            try {
                ServerListPing.Pong pong = ServerListPing.ping(host, port, POLL_TIMEOUT_MS);

                return ToolDispatcher.text(
                        "%s:%d is up after %d attempt(s): %s, %d of %d players online."
                                .formatted(host, port, attempts, pong.version(), pong.online(), pong.max()));
            } catch (IOException e) {
                lastFailure = e.getMessage();
            }

            try {
                TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolDispatcher.failure("the wait was interrupted");
            }
        }

        return ToolDispatcher.failure(
                "%s:%d did not come up within %dms. %d attempt(s), the last saying: %s."
                        .formatted(host, port, timeoutMs, attempts, lastFailure));
    }

    private static void append(StringBuilder body, String label, String value) {
        if (value != null) {
            body.append("\n  ").append(label).append(": ").append(value);
        }
    }

    private McpSchema.CallToolResult read(ToolSpec spec, String feed, Map<String, Object> arguments) {
        BotSession bot = bots.resolve(ToolDispatcher.stringArg(arguments, "bot"));
        ToolDispatcher.kindCheck(spec, bot);
        int count = intArg(arguments, "count", defaultCountOf(feed));

        List<FeedEntry> lines = bot.feed(feed).recent(count);

        if (lines.isEmpty()) {
            return ToolDispatcher.text("The server has not sent %s yet.".formatted(withArticle(nounOf(feed))));
        }

        boolean manySources = lines.stream().map(FeedEntry::source).distinct().count() > 1;
        String body = lines.stream().map(line -> describe(line, manySources))
                .reduce((a, b) -> a + "\n" + b).orElse("");

        return ToolDispatcher.text(spec.untrusted() ? Trust.mark(body) : body);
    }

    private McpSchema.CallToolResult await(ToolSpec spec, String feed, Map<String, Object> arguments) {
        BotSession bot = bots.resolve(ToolDispatcher.stringArg(arguments, "bot"));
        ToolDispatcher.kindCheck(spec, bot);
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
        if (showing != null && pattern.matcher(showing.rendered()).find()) {
            return ToolDispatcher.text(Trust.mark(
                    "The latest %s already shows: %s".formatted(nounOf(feed), showing.rendered())));
        }

        try {
            WaitOutcome outcome = bot.waitOn(feed, line -> pattern.matcher(line.rendered()).find(), timeoutMs)
                    .get(timeoutMs + 2_000L, TimeUnit.MILLISECONDS);

            return switch (outcome) {
                case WaitOutcome.Matched matched -> ToolDispatcher.text(Trust.mark(
                        "Matched: (%s) %s".formatted(matched.entry().source(), matched.entry().rendered())));

                case WaitOutcome.TimedOut timedOut -> ToolDispatcher.failure(
                        "nothing on the %s feed matched /%s/ within %dms."
                                .formatted(feed, source, timedOut.waitedMs()));

                /*
                Being kicked after four seconds and matching nothing in ten are different facts.
                Reporting the first as the second sends you looking in the wrong place.
                */
                case WaitOutcome.Abandoned abandoned -> ToolDispatcher.failure(
                        "bot \"%s\" stopped while waiting on the %s feed for /%s/ (%s). %dms elapsed of %dms."
                                .formatted(bot.name(), feed, source, abandoned.reason(),
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
                .map(bot -> "  %s (%s): %s".formatted(bot.name(), bot.kind(), describe(bot)))
                .reduce((a, b) -> a + "\n" + b).orElse("");

        return ToolDispatcher.text("%d bot(s):\n%s".formatted(all.size(), body));
    }

    /** "idle" is the protocol's word; "linked, not in a world" is what it means to a reader. */
    private static String describe(BotSession bot) {
        Messages.Status status = bot.status();

        if (status == null || "idle".equals(status.state())) {
            return "linked, not in a world";
        }
        if ("ready".equals(status.state())) {
            return Boolean.TRUE.equals(status.dead())
                    ? "on %s, dead".formatted(status.address())
                    : "on " + status.address();
        }
        return status.reason() == null ? status.state()
                : "%s (%s)".formatted(status.state(), status.reason());
    }

    private static String describe(FeedEntry line, boolean withSource) {
        String repeats = line.repeats() > 1
                ? " (shown %d times, first at %s)".formatted(line.repeats(), Instant.ofEpochMilli(line.firstSeen()))
                : "";
        String source = withSource ? line.source() + ": " : "";
        return "[%s] %s%s%s".formatted(Instant.ofEpochMilli(line.seen()), source, line.rendered(), repeats);
    }

    /**
     * The bare noun, because the two sentences it goes into need different articles: "has not sent
     * a toast" and "the latest toast". Returning it with "a" already on read "The a toast already
     * shows" on every feed.
     */
    private static String nounOf(String feed) {
        return switch (feed) {
            case "chat" -> "chat line";
            case "actionBar" -> "action bar";
            case "title" -> "title";
            case "dialog" -> "dialog";
            case "toast" -> "toast";
            default -> "sound or particle";
        };
    }

    private static String withArticle(String noun) {
        return ("aeiou".indexOf(noun.charAt(0)) >= 0 ? "an " : "a ") + noun;
    }

    /**
     * How many lines a read shows when not told. Chat is the one feed a server writes a lot to --
     * a join's MOTD, a quest prompt, a plugin's error -- and the line that says why something
     * failed is often the sixth one back. The catalogue advertises the same numbers.
     */
    private static int defaultCountOf(String feed) {
        return "chat".equals(feed) ? 20 : 5;
    }

    private static int intArg(Map<String, Object> arguments, String name, int fallback) {
        Object value = arguments == null ? null : arguments.get(name);
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
