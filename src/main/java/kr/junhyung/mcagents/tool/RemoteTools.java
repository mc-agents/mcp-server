package kr.junhyung.mcagents.tool;

import kr.junhyung.mcagents.bot.BotSession;
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
            return present(spec, bot, result);
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

    /** Composed tools send to a bot and then read the server's own buffers. Not yet wired. */
    public McpSchema.CallToolResult compose(ToolSpec spec, BotSession bot, Map<String, Object> arguments) {
        return call(spec, bot, arguments);
    }

    private McpSchema.CallToolResult present(ToolSpec spec, BotSession bot, Messages.Result result) {
        if (!result.ok()) {
            return unsupported(result) ? withdraw(spec, bot) : ToolDispatcher.failure(result.text());
        }

        String body = Renderers.render(spec.name(), asNode(result.data())).orElse(result.text());

        if (spec.untrusted()) {
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
