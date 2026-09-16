package kr.junhyung.mcagents.tool;

import kr.junhyung.mcagents.catalog.Catalog;
import kr.junhyung.mcagents.catalog.ToolSpec;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Puts the whole catalogue on the MCP endpoint.
 *
 * <p>Every tool is advertised whether or not a bot is connected. An MCP client reads
 * {@code tools/list} once when the session opens, so a client that connects before the first bot
 * has to see everything — a list assembled from whoever happens to be linked would leave an agent
 * unable to call a tool it could have called a second later.
 *
 * <p>What a bot can actually run is settled per call, not here. A tool only one kind of bot
 * supports says so in its description, and asking for it on the wrong kind is answered with the
 * kind that does.
 */
@Configuration
public class ToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistrar.class);

    private final Catalog catalog;
    private final ToolDispatcher dispatcher;

    public ToolRegistrar(Catalog catalog, ToolDispatcher dispatcher) {
        this.catalog = catalog;
        this.dispatcher = dispatcher;
    }

    @Bean
    public List<McpServerFeatures.SyncToolSpecification> mcAgentTools() {
        return catalog.all().stream().map(this::specify).toList();
    }

    private McpServerFeatures.SyncToolSpecification specify(ToolSpec spec) {
        McpSchema.Tool tool = new McpSchema.Tool(
                spec.name(),
                null,
                spec.advertisedDescription(catalog.kinds()),
                schemaOf(spec),
                null,
                annotationsOf(spec),
                Map.of(
                        "mcAgents/kinds", spec.kinds(),
                        "mcAgents/route", spec.route().name().toLowerCase(java.util.Locale.ROOT)),
                null);

        return new McpServerFeatures.SyncToolSpecification(
                tool, (exchange, request) -> dispatcher.call(spec, request.arguments(), progressOf(exchange, request)));
    }

    /**
     * The hints a client uses to decide what to ask a person about. {@code readOnly} and
     * {@code destructive} are the catalogue's; idempotence is the reads plus the two setters
     * whose second call changes nothing. Every tool acts on one server the bot is already on,
     * which is what a closed world means here. No title: the name is the title.
     */
    static McpSchema.ToolAnnotations annotationsOf(ToolSpec spec) {
        return new McpSchema.ToolAnnotations(
                null,
                spec.readOnly(),
                spec.destructive(),
                spec.readOnly() || "look-at".equals(spec.name()) || "set-stance".equals(spec.name()),
                false,
                null);
    }

    /**
     * A progress channel exists only when the client asked for one. Sending on it is best effort:
     * a client that has gone away between polls is the call's problem to notice when it ends,
     * not a reason to fail it midway.
     */
    private static Progress progressOf(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Object token = request.meta() == null ? null : request.meta().get("progressToken");

        if (token == null) {
            return Progress.NONE;
        }
        return (message, elapsedMs, totalMs) -> {
            try {
                exchange.progressNotification(new McpSchema.ProgressNotification(
                        token, (double) elapsedMs, (double) totalMs, message, null));
            } catch (RuntimeException e) {
                log.debug("a progress notification for {} was not delivered: {}", request.name(), e.toString());
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemaOf(ToolSpec spec) {
        return (Map<String, Object>) (Map<?, ?>) spec.inputSchema();
    }
}
