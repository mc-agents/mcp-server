package kr.junhyung.mcagents.e2e;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;

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
    private final String kind;
    private final Map<String, List<?>> kinds;

    Agent(int port, String kind) {
        client = McpClient.sync(HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port)
                .endpoint("/mcp")
                .build())
            .requestTimeout(PATIENCE)
            .build();
        client.initialize();

        this.kind = kind;
        this.kinds = client.listTools().tools().stream().collect(Collectors.toMap(
            McpSchema.Tool::name,
            tool -> tool.meta() == null ? List.of() : (List<?>) tool.meta().getOrDefault("mcAgents/kinds", List.of())));
    }

    /** Whether the catalogue gives this tool to the kind of bot being driven. */
    boolean runs(String tool) {
        return kinds.getOrDefault(tool, List.of()).contains(kind);
    }

    /**
     * A case that needs a tool this kind of bot does not have is skipped, not failed.
     *
     * <p>The catalogue is what says which kind runs what, so every call asks it rather than every case
     * listing the tools it needs: a tool that gains a kind turns on every case that uses it, and
     * nothing here has to be edited for that to happen.
     *
     * <p>A case calls this itself, before anything else, only when it puts the bot somewhere a tool
     * is needed to get it out of. Skipping at the first call it cannot make is too late for those: the
     * end-credits case had already sent an azalea bot through the portal when it reached
     * close-window, and a bot that never asks to leave the credits is held outside every world, so
     * each case after it failed on a teleport that could not land.
     */
    void requires(String... tools) {
        for (String tool : tools) {
            if (!runs(tool)) {
                Assumptions.abort("a " + kind + " bot does not run " + tool);
            }
        }
    }

    /** The text an agent is shown, with the blob parts left out. */
    String call(String tool, Map<String, Object> args) {
        requires(tool);
        McpSchema.CallToolResult answer = client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(args).build());

        return text(answer);
    }

    /**
     * A call the run cannot continue without.
     *
     * <p>{@link #call} hands back what a tool said whether or not it worked, which is what an
     * assertion wants. Setting the world up is the other case: a join that failed and was ignored
     * left every case after it reporting that the bot was not in a world, which says nothing about
     * why.
     */
    String mustCall(String tool, Map<String, Object> args) {
        requires(tool);
        McpSchema.CallToolResult answer = client.callTool(
            McpSchema.CallToolRequest.builder(tool).arguments(args).build());

        if (Boolean.TRUE.equals(answer.isError())) {
            throw new IllegalStateException(tool + " failed: " + text(answer));
        }
        return text(answer);
    }

    /** The same, for a tool expected to refuse: the message is the answer. */
    String refusal(String tool, Map<String, Object> args) {
        requires(tool);
        McpSchema.CallToolResult answer = client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(args).build());

        if (!Boolean.TRUE.equals(answer.isError())) {
            throw new AssertionError(tool + " was expected to refuse and answered: " + text(answer));
        }
        return text(answer);
    }

    /** How many parts of the answer were not text, which is how a screenshot arrives. */
    int blobs(String tool, Map<String, Object> args) {
        requires(tool);
        McpSchema.CallToolResult answer = client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(args).build());

        return images(answer);
    }

    /** The text an agent reads and how many images came beside it. */
    record Answer(String text, int images) {}

    /**
     * A call that has to work, answered with its text and its image parts together: a tool that
     * attaches a frame beside its sentence is asserted on both, from the one call.
     */
    Answer answer(String tool, Map<String, Object> args) {
        requires(tool);
        McpSchema.CallToolResult answer = client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(args).build());

        if (Boolean.TRUE.equals(answer.isError())) {
            throw new IllegalStateException(tool + " failed: " + text(answer));
        }
        return new Answer(text(answer), images(answer));
    }

    List<String> tools() {
        return client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
    }

    private static int images(McpSchema.CallToolResult answer) {
        return (int) answer.content().stream().filter(part -> !(part instanceof McpSchema.TextContent)).count();
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
