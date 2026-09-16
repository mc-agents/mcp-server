package kr.junhyung.mcagents.tool;

import kr.junhyung.mcagents.bot.BotSession;
import kr.junhyung.mcagents.bot.FeedEntry;
import kr.junhyung.mcagents.catalog.Catalog;
import kr.junhyung.mcagents.catalog.ToolSpec;
import kr.junhyung.mcagents.protocol.Messages;
import kr.junhyung.mcagents.render.Renderers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Component;

/** Tools a bot answers. */
@Component
public class RemoteTools {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Between two polls of a waited-on tool. A tick is 50ms and nothing here changes that fast. */
    private static final int POLL_MS = 500;

    /** One walk at a time per bot: two of them cancel each other and then both report success. */
    private final Map<String, Set<String>> busy = new ConcurrentHashMap<>();

    /** A wait polls another tool, and the catalogue is where it says which. */
    private final Catalog catalog;

    public RemoteTools(Catalog catalog) {
        this.catalog = catalog;
    }

    public McpSchema.CallToolResult call(ToolSpec spec, BotSession bot, Map<String, Object> arguments) {
        return call(spec, bot, arguments, true);
    }

    private McpSchema.CallToolResult call(ToolSpec spec, BotSession bot, Map<String, Object> arguments,
            boolean mark) {
        if (spec.needsWorld() && !bot.isReady()) {
            return ToolDispatcher.failure(
                    "bot \"%s\" is not in a world. Its state is %s. Use get-bot-status to see why."
                            .formatted(bot.name(), state(bot)));
        }

        Map<String, Object> wire = Normaliser.normalise(spec, arguments);
        int deadline = Normaliser.deadlineOf(spec, wire);

        if (spec.exclusive() && !claim(bot.name(), spec.name())) {
            return ToolDispatcher.failure(
                    "bot \"%s\" is already running an exclusive tool. Wait for it or use another bot."
                            .formatted(bot.name()));
        }

        try {
            bot.touch();
            Messages.Result result = bot.link().call(spec.name(), wire, deadline)
                    .get(deadline + 5_000L, TimeUnit.MILLISECONDS);
            return present(spec, bot, result, mark);
        } catch (TimeoutException e) {
            return ToolDispatcher.failure(
                    "%s did not finish within %dms and the bot did not say why.".formatted(spec.name(), deadline));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolDispatcher.failure("the call was interrupted");
        } catch (ExecutionException e) {
            return ToolDispatcher.failure("%s failed: %s".formatted(spec.name(), e.getCause()));
        } finally {
            if (spec.exclusive()) {
                release(bot.name(), spec.name());
            }
        }
    }

    /**
     * A call plus whatever the server's own buffers caught while it ran.
     *
     * <p>{@code run-command} is the case this exists for. A command's answer is not a return value
     * but what the server sends next -- chat, or on a server whose commands open menus, a dialog or
     * a title -- so the tool is only useful if that comes with it, and only what arrived after the
     * command was sent counts. The link applies frames on one thread in arrival order, which is
     * what makes "after" mean anything.
     *
     * <p>Chat is the answer when there is any. The dialog and title feeds are read only when there
     * is none, because a command that replies in chat and also draws a title is answered by the
     * chat, and an agent that got "no chat" for a command that opened a dialog went looking for a
     * server error. Between those two nothing wins: a quest command that opens its dialog and
     * throws a title has done both, and both are shown. The action bar is left out: a plugin HUD
     * redraws it every few ticks whether or not a command ran.
     */
    public McpSchema.CallToolResult compose(ToolSpec spec, BotSession bot, Map<String, Object> arguments) {
        if (spec.watches() != null) {
            return awaitMatch(spec, bot, arguments);
        }

        long from = bot.feed("chat").nextSeq();
        long dialogsFrom = bot.feed("dialog").nextSeq();
        long titlesFrom = bot.feed("title").nextSeq();

        /*
        run-command is marked untrusted because of the chat it collects, not because of the
        sentence the bot returns. Letting call() mark that sentence puts the notice in the middle
        of it; the notice goes on the block below, where the server's own words actually are.
        */
        McpSchema.CallToolResult answer = call(spec, bot, arguments, false);

        if (answer.isError()) {
            return answer;
        }
        if ("switch-server".equals(spec.name())) {
            return arrived(bot, answer);
        }
        if (!"run-command".equals(spec.name())) {
            return answer;
        }

        /*
        collectMs never crosses the wire: the buffer being read is the server's, so the bot has
        nothing to do with it. That also means the Normaliser never sees it, and the bounds the
        catalogue advertises have to be applied here.
        */
        int collectMs = Math.clamp(intOf(arguments == null ? null : arguments.get("collectMs"), 1_000),
                0, 10_000);

        sleep(collectMs);

        List<FeedEntry> replies = bot.feed("chat").since(from);

        if (!replies.isEmpty()) {
            return ToolDispatcher.text("%s The server replied %s:\n%s"
                    .formatted(textOf(answer), Trust.NOTICE, lines(replies)));
        }

        String silence = "%s The server sent no chat in the %dms after it".formatted(textOf(answer), collectMs);
        /* A run that ends is a line on the dialog feed too, and one of those is not a dialog opening. */
        List<FeedEntry> dialogs = bot.feed("dialog").since(dialogsFrom).stream()
                .filter(line -> !"closed".equals(line.source()))
                .toList();
        List<FeedEntry> titles = bot.feed("title").since(titlesFrom);

        if (dialogs.isEmpty() && titles.isEmpty()) {
            return ToolDispatcher.text(silence + ". If the command opens a menu, read-window shows it.");
        }

        String shown = silence;
        if (!dialogs.isEmpty()) {
            shown += ", but a dialog opened %s:\n%s".formatted(Trust.NOTICE, lines(dialogs));
        }
        if (!titles.isEmpty()) {
            /* Which of the two a line is, since a subtitle on its own reads like a title. */
            String titled = lines(titles, line -> line.source() + ": " + line.rendered());
            shown += dialogs.isEmpty()
                    ? ", but a title showed %s:\n%s".formatted(Trust.NOTICE, titled)
                    : "\nand a title showed:\n%s".formatted(titled);
        }
        return ToolDispatcher.text(shown);
    }

    private static String lines(List<FeedEntry> entries) {
        return lines(entries, FeedEntry::rendered);
    }

    private static String lines(List<FeedEntry> entries, Function<FeedEntry, String> shown) {
        return entries.stream()
                .map(line -> "  " + shown.apply(line))
                .reduce((a, b) -> a + "\n" + b).orElse("");
    }

    /**
     * Wait for a reading tool to answer something a pattern matches.
     *
     * <p>Four feeds are pushed by the bot and can be waited on where they are kept. The rest of
     * what a server changes -- a sidebar counting a quest up, a boss bar, a hologram, the tab list
     * -- is state a tool reads when asked, with nothing to wake a waiter, so this asks again until
     * the answer says what the caller is waiting for.
     *
     * <p>Polling and not a subscription, because the alternative is every bot pushing every piece
     * of state it holds on the chance somebody waits for it. The cost is one cheap call a poll,
     * which is what a caller would otherwise write as a loop.
     */
    private McpSchema.CallToolResult awaitMatch(ToolSpec spec, BotSession bot, Map<String, Object> arguments) {
        ToolSpec watched = catalog.require(spec.watches());
        Pattern pattern = compile(arguments == null ? null : arguments.get("pattern"));

        if (pattern == null) {
            return ToolDispatcher.failure("%s needs a pattern to wait for.".formatted(spec.name()));
        }

        int timeoutMs = Math.clamp(intOf(arguments == null ? null : arguments.get("timeoutMs"), 10_000),
                100, 120_000);
        Map<String, Object> passed = withoutWaitArguments(arguments);
        long deadline = System.currentTimeMillis() + timeoutMs;
        McpSchema.CallToolResult last = null;

        while (System.currentTimeMillis() < deadline) {
            last = call(watched, bot, passed, watched.untrusted());

            if (last.isError()) {
                return last;
            }
            if (pattern.matcher(textOf(last)).find()) {
                return last;
            }
            sleep(POLL_MS);
        }

        /* What it last said, because "nothing matched" on its own sends a caller back to guess. */
        return ToolDispatcher.failure("nothing %s answered matched /%s/ within %dms. It last said: %s"
                .formatted(watched.name(), pattern.pattern(), timeoutMs,
                        last == null ? "nothing" : textOf(last)));
    }

    /** Everything the watched tool might want, which is whatever the wait itself does not. */
    private static Map<String, Object> withoutWaitArguments(Map<String, Object> arguments) {
        if (arguments == null) {
            return Map.of();
        }

        Map<String, Object> passed = new java.util.LinkedHashMap<>(arguments);
        passed.remove("pattern");
        passed.remove("timeoutMs");

        return passed;
    }

    private static Pattern compile(Object raw) {
        if (!(raw instanceof String source) || source.isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(source);
        } catch (PatternSyntaxException broken) {
            throw new IllegalArgumentException("the pattern /%s/ is not a regular expression: %s"
                    .formatted(source, broken.getDescription()));
        }
    }

    /**
     * Say where the bot ended up, from the server's own record rather than the bot's sentence.
     *
     * <p>A proxy switch that reports success and leaves the bot on the old backend is the failure
     * worth catching here, and only the status says which backend it is on.
     */
    private static McpSchema.CallToolResult arrived(BotSession bot, McpSchema.CallToolResult answer) {
        Messages.Status now = bot.status();

        if (now == null || !"ready".equals(now.state())) {
            return ToolDispatcher.failure(
                    "bot \"%s\" reported switching but is %s. get-bot-status has what it last said."
                            .formatted(bot.name(), now == null ? "in no world" : now.state()));
        }
        return ToolDispatcher.text("%s It is on %s as %s."
                .formatted(textOf(answer), now.address(), now.username()));
    }

    private static int intOf(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static String textOf(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(content -> ((McpSchema.TextContent) content).text())
                .findFirst().orElse("");
    }

    private static void sleep(int millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private McpSchema.CallToolResult present(ToolSpec spec, BotSession bot, Messages.Result result,
            boolean mark) {
        if (!result.ok()) {
            return unsupported(result) ? withdraw(spec, bot) : ToolDispatcher.failure(result.text());
        }

        /*
        A structured tool's words are the server's, built from the DTO. A bot that sends none has
        disagreed with the catalogue, and saying that is worth far more than the exception the
        renderer throws when handed nothing -- which is what a caller used to get.
        */
        if (spec.structured() && result.data() == null) {
            return ToolDispatcher.failure(
                    "bot \"%s\" (kind: %s) answered \"%s\" without the data the catalogue says it sends. Its build disagrees with this server's catalogue (%s)."
                            .formatted(bot.name(), bot.kind(), spec.name(), spec.wireSchemaHash()));
        }

        String body = Renderers.render(spec.name(), asNode(result.data())).orElse(result.text());

        if (spec.untrusted() && mark) {
            body = Trust.mark(body);
        }
        if (result.blobs() == null || result.blobs().isEmpty()) {
            return ToolDispatcher.text(body);
        }
        return withBlobs(bot, body, result.blobs());
    }

    /*
    An image with no words beside it leaves an agent guessing which bot it came from and when. The
    text block carries that, and the trust notice, before anything drawn by a server is shown.
    */
    private McpSchema.CallToolResult withBlobs(BotSession bot, String body, List<Messages.Blob> blobs) {
        List<McpSchema.Content> content = new ArrayList<>();
        content.add(McpSchema.TextContent.builder(body).build());

        for (Messages.Blob blob : blobs) {
            byte[] bytes = bot.link().takeBlob(UUID.fromString(blob.id()));
            if (bytes == null) {
                return ToolDispatcher.failure(
                        "the bot named a %s attachment it never sent".formatted(blob.mime()));
            }
            content.add(McpSchema.ImageContent
                    .builder(Base64.getEncoder().encodeToString(bytes), blob.mime())
                    .build());
        }
        return McpSchema.CallToolResult.builder().content(content).isError(false).build();
    }

    /* A structured result arrives as whatever Jackson made of it; the renderers want a tree. */
    private static JsonNode asNode(Object data) {
        return data == null ? null : MAPPER.valueToTree(data);
    }

    private static boolean unsupported(Messages.Result result) {
        return result.error() != null && "unsupported".equals(result.error().errorClass());
    }

    /** A bot that turns out not to have a tool loses it, so the next call is refused without a trip. */
    private McpSchema.CallToolResult withdraw(ToolSpec spec, BotSession bot) {
        bot.withdraw(spec.name());
        return ToolDispatcher.failure(
                "bot \"%s\" (kind: %s) does not implement \"%s\" after all. It will not be offered to this bot again."
                        .formatted(bot.name(), bot.kind(), spec.name()));
    }

    private static String state(BotSession bot) {
        return bot.status() == null ? "unknown" : bot.status().state();
    }

    private boolean claim(String bot, String tool) {
        return busy.computeIfAbsent(bot, key -> ConcurrentHashMap.newKeySet()).isEmpty()
                && busy.get(bot).add(tool);
    }

    private void release(String bot, String tool) {
        Set<String> held = busy.get(bot);
        if (held != null) {
            held.remove(tool);
        }
    }
}
