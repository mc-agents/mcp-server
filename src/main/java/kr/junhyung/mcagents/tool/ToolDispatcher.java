package kr.junhyung.mcagents.tool;

import kr.junhyung.mcagents.bot.BotRegistry;
import kr.junhyung.mcagents.bot.BotSession;
import kr.junhyung.mcagents.catalog.ToolSpec;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Turns a tool call into whatever answers it.
 *
 * <p>Where a call goes is the catalogue's business, not the caller's: some are answered from the
 * server's own buffers, some go to a bot, some are both. An agent sees one tool either way.
 */
@Component
public class ToolDispatcher {

    private final BotRegistry bots;
    private final LocalTools local;
    private final RemoteTools remote;
    private final Orchestration orchestration;

    public ToolDispatcher(BotRegistry bots, LocalTools local, RemoteTools remote,
            Orchestration orchestration) {
        this.bots = bots;
        this.local = local;
        this.remote = remote;
        this.orchestration = orchestration;
    }

    public McpSchema.CallToolResult call(ToolSpec spec, Map<String, Object> arguments) {
        try {
            return switch (spec.route()) {
                case LOCAL -> local.call(spec, arguments);
                case RPC -> remote.call(spec, resolve(spec, arguments), arguments);
                case COMPOSE -> remote.compose(spec, resolve(spec, arguments), arguments);
                case ORCHESTRATE -> orchestration.call(spec, arguments);
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            return failure(e.getMessage());
        } catch (RuntimeException e) {
            return failure("%s failed: %s".formatted(spec.name(), e));
        }
    }

    /**
     * Refuse a tool this bot cannot run before anything is sent, and name a kind that can. The
     * alternative is a round trip that ends in the same refusal with less to act on.
     */
    private BotSession resolve(ToolSpec spec, Map<String, Object> arguments) {
        BotSession session = bots.resolve(stringArg(arguments, "bot"));

        if (!spec.supportedBy(session.kind())) {
            throw new IllegalStateException(
                    "\"%s\" is not supported by bot \"%s\" (kind: %s). Bots of kind %s support it: either use one (list-bots shows each bot's kind) or join-server with that kind."
                            .formatted(spec.name(), session.name(), session.kind(),
                                    String.join(" or ", spec.kinds())));
        }
        return session;
    }

    static String stringArg(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    static McpSchema.CallToolResult text(String body) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(McpSchema.TextContent.builder(body).build()))
                .isError(false)
                .build();
    }

    static McpSchema.CallToolResult failure(String body) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(McpSchema.TextContent.builder("Failed: " + body).build()))
                .isError(true)
                .build();
    }
}
