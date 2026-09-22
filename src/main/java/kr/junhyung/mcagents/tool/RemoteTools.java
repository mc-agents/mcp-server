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
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Tools a bot answers. */
@Component
public class RemoteTools {

    private static final Logger log = LoggerFactory.getLogger(RemoteTools.class);

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
        Exchange exchange;

        try {
            exchange = fetch(spec, bot, arguments);
        } catch (IllegalStateException refused) {
            return ToolDispatcher.failure(refused.getMessage());
        }
        return present(spec, bot, exchange.result(), arguments, exchange.deadline(), mark);
    }

    /** A bot's answer as it sent it, and how long it was given. */
    record Exchange(Messages.Result result, int deadline) {}

    /**
     * The bot's answer as it sent it, for a tool the server reads the data of rather than the words.
     *
     * <p>A survey assembles a box from tile after tile of read-region, and what it wants from each
     * is the palette and the runs, not the sentence {@link #present} would make of them. Everything
     * that guards a call still happens here -- the world check, the box check, the bounds, the
     * claim -- and what would have been a failure to answer with is thrown, since a caller of this
     * is composing an answer of its own.
     */
    Exchange fetch(ToolSpec spec, BotSession bot, Map<String, Object> arguments) {
        if (spec.needsWorld() && !bot.isReady()) {
            throw new IllegalStateException(
                    "bot \"%s\" is not in a world. Its state is %s. Use get-bot-status to see why."
                            .formatted(bot.name(), state(bot)));
        }

        /*
        A box is six numbers whose product decides how much the bot is asked to read, and a schema
        can bound a number but not a product: a corner mistyped by a thousand is schema-valid and a
        billion blocks. Region refuses it here, in the terms Normaliser refuses a value outside its
        range, rather than after the round trip that would end in the same refusal -- and hands
        back the corners it measured, because a guard that checks one object and forwards another
        is only guarding the copy.
        */
        Map<String, Object> checked = "read-region".equals(spec.name())
                ? Region.settle(spec.name(), arguments)
                : arguments;

        Map<String, Object> wire = Normaliser.normalise(spec, checked);
        int deadline = Normaliser.deadlineOf(spec, wire);

        if (spec.exclusive() && !claim(bot.name(), spec.name())) {
            throw new IllegalStateException(busyWith(bot));
        }

        try {
            bot.touch();
            return new Exchange(bot.link().call(spec.name(), wire, deadline)
                    .get(deadline + 5_000L, TimeUnit.MILLISECONDS), deadline);
        } catch (TimeoutException e) {
            throw new IllegalStateException(
                    "%s did not finish within %dms and the bot did not say why.".formatted(spec.name(), deadline));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the call was interrupted");
        } catch (ExecutionException e) {
            throw new IllegalStateException("%s failed: %s".formatted(spec.name(), e.getCause()));
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
        return compose(spec, bot, arguments, Progress.NONE);
    }

    public McpSchema.CallToolResult compose(ToolSpec spec, BotSession bot, Map<String, Object> arguments,
            Progress progress) {
        if (spec.watches() != null) {
            return awaitMatch(spec, bot, arguments, progress);
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
                    .formatted(ToolDispatcher.textOf(answer), Trust.NOTICE, lines(replies)));
        }

        String silence = "%s The server sent no chat in the %dms after it".formatted(ToolDispatcher.textOf(answer), collectMs);
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
    private McpSchema.CallToolResult awaitMatch(ToolSpec spec, BotSession bot, Map<String, Object> arguments,
            Progress progress) {
        ToolSpec watched = catalog.require(spec.watches());
        /* The pattern never crosses the wire, so the length the catalogue advertises is held here. */
        Map<String, Object> bounded = Normaliser.bound(spec, arguments);
        Pattern pattern = compile(bounded == null ? null : bounded.get("pattern"));

        if (pattern == null) {
            return ToolDispatcher.failure("%s needs a pattern to wait for.".formatted(spec.name()));
        }

        int timeoutMs = Math.clamp(intOf(arguments == null ? null : arguments.get("timeoutMs"), 10_000),
                100, 120_000);
        Map<String, Object> passed = withoutWaitArguments(arguments);
        long started = System.currentTimeMillis();
        long deadline = started + timeoutMs;
        McpSchema.CallToolResult last = null;

        while (System.currentTimeMillis() < deadline) {
            last = call(watched, bot, passed, watched.untrusted());

            if (last.isError()) {
                return last;
            }
            if (pattern.matcher(ToolDispatcher.textOf(last)).find()) {
                return last;
            }
            progress.report("%s last said: %s".formatted(watched.name(), ToolDispatcher.firstLineOf(last)),
                    System.currentTimeMillis() - started, timeoutMs);
            sleep(POLL_MS);
        }

        /* What it last said, because "nothing matched" on its own sends a caller back to guess. */
        return ToolDispatcher.failure("nothing %s answered matched /%s/ within %dms. It last said: %s"
                .formatted(watched.name(), pattern.pattern(), timeoutMs,
                        last == null ? "nothing" : ToolDispatcher.textOf(last)));
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
                .formatted(ToolDispatcher.textOf(answer), now.address(), now.username()));
    }

    private static int intOf(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static void sleep(int millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private McpSchema.CallToolResult present(ToolSpec spec, BotSession bot, Messages.Result result,
            Map<String, Object> arguments, int deadline, boolean mark) {
        if (!result.ok()) {
            return failed(spec, bot, result, deadline);
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

        String body = Renderers.render(spec.name(), asNode(result.data()), arguments).orElse(result.text());

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

    /**
     * What a failed result does to the session is decided by its class, and docs/bot-protocol.md
     * is the table this follows. Only two classes change anything: a tool the bot does not have,
     * or cannot read the arguments of, is withdrawn so the next call is refused without a round
     * trip. The rest are reported. A {@code bot} failure is not what reaps the session -- the
     * heartbeat and the next status do that -- so it points at the tool that shows them.
     *
     * <p>The message is the bot's, and a bot that read the server's own words into it (a kick
     * reason, a refused command) has put untrusted text in a failure, which is marked the way a
     * success would be. run-command's success keeps its notice for the chat block; a failure has
     * no such block, so it is marked here whatever the caller asked.
     */
    private McpSchema.CallToolResult failed(ToolSpec spec, BotSession bot, Messages.Result result, int deadline) {
        Messages.Failure error = result.error();
        String errorClass = error == null || error.errorClass() == null ? "tool" : error.errorClass();
        String said = result.text() != null ? result.text()
                : error != null && error.message() != null ? error.message()
                : "the bot gave no reason";
        String text = spec.untrusted() ? Trust.mark(said) : said;

        String body = switch (errorClass) {
            case "unsupported" -> withdraw(spec, bot,
                    "bot \"%s\" (kind: %s) does not implement \"%s\" after all. It will not be offered to this bot again."
                            .formatted(bot.name(), bot.kind(), spec.name()));
            /*
            Not withdrawn: a bot refuses arguments the schema cannot judge -- a slot outside the
            window it has open, a hand it does not know -- and a build that disagrees with the
            catalogue never got the tool offered, since the hashes are compared at the handshake.
            */
            case "args" -> text;
            case "bot" -> "%s Use get-bot-status to inspect it.".formatted(text);
            case "timeout" -> "%s did not finish within %dms: %s".formatted(spec.name(), deadline, text);
            default -> text;
        };

        return ToolDispatcher.failure(error != null && error.retryable() ? body + " (retryable)" : body);
    }

    /** A bot that turns out not to have a tool loses it, so the next call is refused without a trip. */
    private String withdraw(ToolSpec spec, BotSession bot, String why) {
        log.warn("withdrawing {} from bot \"{}\": {}", spec.name(), bot.name(), why);
        bot.withdraw(spec.name());
        return why;
    }

    private static String state(BotSession bot) {
        return bot.status() == null ? "unknown" : bot.status().state();
    }

    /**
     * Hold a bot's exclusive claim across a run of calls.
     *
     * <p>A tool the server composes out of several {@code run-command} calls is exclusive as a
     * whole and not one call at a time: two edits interleaving their //pos1 and //pos2 would each
     * end up working on the box the other selected. The claim is the same one a single exclusive
     * call takes, so a walk cannot start on top of an edit either.
     */
    McpSchema.CallToolResult exclusively(ToolSpec spec, BotSession bot,
            Supplier<McpSchema.CallToolResult> body) {
        if (!claim(bot.name(), spec.name())) {
            return ToolDispatcher.failure(busyWith(bot));
        }
        try {
            return body.get();
        } finally {
            release(bot.name(), spec.name());
        }
    }

    private String busyWith(BotSession bot) {
        return "bot \"%s\" is already running %s. Wait for it or use another bot."
                .formatted(bot.name(), running(bot.name()));
    }

    private boolean claim(String bot, String tool) {
        return busy.computeIfAbsent(bot, key -> ConcurrentHashMap.newKeySet()).isEmpty()
                && busy.get(bot).add(tool);
    }

    /** The tool holding the claim, read after a claim failed; it may have finished in between. */
    private String running(String bot) {
        Set<String> held = busy.get(bot);
        return held == null || held.isEmpty() ? "an exclusive tool"
                : "%s, which is exclusive".formatted(String.join(", ", held));
    }

    private void release(String bot, String tool) {
        Set<String> held = busy.get(bot);
        if (held != null) {
            held.remove(tool);
        }
    }
}
