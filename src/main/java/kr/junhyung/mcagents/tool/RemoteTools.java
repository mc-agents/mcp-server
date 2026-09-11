package kr.junhyung.mcagents.tool;

import kr.junhyung.mcagents.bot.BotSession;
import kr.junhyung.mcagents.bot.FeedEntry;
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
import org.springframework.stereotype.Component;

/** Tools a bot answers. */
@Component
public class RemoteTools {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** One walk at a time per bot: two of them cancel each other and then both report success. */
    private final Map<String, Set<String>> busy = new ConcurrentHashMap<>();

    public McpSchema.CallToolResult call(ToolSpec spec, BotSession bot, Map<String, Object> arguments) {
        return call(spec, bot, arguments, true);
    }

    private McpSchema.CallToolResult call(ToolSpec spec, BotSession bot, Map<String, Object> arguments,
            boolean mark) {
        if (!bot.isReady()) {
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
     * <p>{@code run-command} is the case this exists for. A command's answer is chat, not a return
     * value, so the tool is only useful if the lines the server sent back come with it, and only
     * the lines that arrived after the command was sent count. The link applies frames on one
     * thread in arrival order, which is what makes "after" mean anything.
     */
    public McpSchema.CallToolResult compose(ToolSpec spec, BotSession bot, Map<String, Object> arguments) {
        long from = bot.feed("chat").nextSeq();

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

        int collectMs = intOf(Normaliser.normalise(spec, arguments).get("collectMs"), 1_000);

        sleep(collectMs);

        List<FeedEntry> replies = bot.feed("chat").since(from);

        if (replies.isEmpty()) {
            return ToolDispatcher.text(
                    "%s The server sent no chat in the %dms after it."
                            .formatted(textOf(answer), collectMs));
        }

        String lines = replies.stream()
                .map(line -> "  " + line.text())
                .reduce((a, b) -> a + "\n" + b).orElse("");

        return ToolDispatcher.text("%s The server replied %s:\n%s"
                .formatted(textOf(answer), Trust.NOTICE, lines));
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
