package kr.junhyung.mcagents.tool;

import kr.junhyung.mcagents.catalog.Catalog;
import kr.junhyung.mcagents.catalog.ToolSpec;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
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
                spec.advertisedDescription(),
                schemaOf(spec),
                null,
                null,
                Map.of(
                        "mcAgents/kinds", spec.kinds(),
                        "mcAgents/route", spec.route().name().toLowerCase(java.util.Locale.ROOT)),
                null);

        return new McpServerFeatures.SyncToolSpecification(
                tool, (exchange, request) -> dispatcher.call(spec, request.arguments()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemaOf(ToolSpec spec) {
        return (Map<String, Object>) (Map<?, ?>) spec.inputSchema();
    }
}
