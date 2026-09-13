package kr.junhyung.mcagents.e2e;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * What an agent sees.
 *
 * <p>Over the MCP endpoint and through the SDK's own client rather than by calling the dispatcher,
 * because the surface is the contract: the argument validation, the untrusted-content notice and
 * the renderers all sit between a tool and the sentence an agent reads, and a test that skips them
 * is not testing what anybody uses.
 */
final class Agent implements AutoCloseable {

    /** A bot that has to walk somewhere, or a server that has to boot, takes more than a moment. */
    private static final Duration PATIENCE = Duration.ofMinutes(3);

    private final McpSyncClient client;

    Agent(int port) {
        client = McpClient.sync(HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port)
                .endpoint("/mcp")
                .build())
            .requestTimeout(PATIENCE)
            .build();
        client.initialize();
    }

    /** The text an agent is shown, with the blob parts left out. */
    String call(String tool, Map<String, Object> args) {
        McpSchema.CallToolResult answer = client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(args).build());

        return text(answer);
    }

    /** The same, for a tool expected to refuse: the message is the answer. */
    String refusal(String tool, Map<String, Object> args) {
        McpSchema.CallToolResult answer = client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(args).build());

        if (!Boolean.TRUE.equals(answer.isError())) {
            throw new AssertionError(tool + " was expected to refuse and answered: " + text(answer));
        }
        return text(answer);
    }

    /** How many parts of the answer were not text, which is how a screenshot arrives. */
    int blobs(String tool, Map<String, Object> args) {
        McpSchema.CallToolResult answer = client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(args).build());

        return (int) answer.content().stream().filter(part -> !(part instanceof McpSchema.TextContent)).count();
    }

    List<String> tools() {
        return client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
    }

    private static String text(McpSchema.CallToolResult answer) {
        return answer.content().stream()
            .filter(McpSchema.TextContent.class::isInstance)
            .map(part -> ((McpSchema.TextContent) part).text())
            .findFirst()
            .orElse("");
    }

    @Override
    public void close() {
        client.close();
    }
}
