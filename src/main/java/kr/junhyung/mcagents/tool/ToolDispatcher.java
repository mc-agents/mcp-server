package kr.junhyung.mcagents.tool;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import kr.junhyung.mcagents.bot.BotRegistry;
import kr.junhyung.mcagents.bot.BotSession;
import kr.junhyung.mcagents.catalog.ToolSpec;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns a tool call into whatever answers it.
 *
 * <p>Where a call goes is the catalogue's business, not the caller's: some are answered from the
 * server's own buffers, some go to a bot, some are both. An agent sees one tool either way.
 */
@Component
public class ToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ToolDispatcher.class);

    private final BotRegistry bots;
    private final LocalTools local;
    private final RemoteTools remote;
    private final Orchestration orchestration;
    private final MeterRegistry meters;

    public ToolDispatcher(BotRegistry bots, LocalTools local, RemoteTools remote,
            Orchestration orchestration, MeterRegistry meters) {
        this.bots = bots;
        this.local = local;
        this.remote = remote;
        this.orchestration = orchestration;
        this.meters = meters;
    }

    public McpSchema.CallToolResult call(ToolSpec spec, Map<String, Object> arguments) {
        return call(spec, arguments, Progress.NONE);
    }

    /**
     * One line per call, whatever happened to it. An agent's transcript says "did not answer
     * within 30000ms" and nothing else; this is the server's side of that sentence, naming the
     * tool, the bot and how long it really took. A failure is worth a WARN on its own, because
     * {@code isError} travels inside an HTTP 200 and no request log will ever show it.
     */
    public McpSchema.CallToolResult call(ToolSpec spec, Map<String, Object> arguments, Progress progress) {
        long started = System.nanoTime();
        McpSchema.CallToolResult result = dispatch(spec, arguments, progress);
        long elapsedNanos = System.nanoTime() - started;
        boolean failed = Boolean.TRUE.equals(result.isError());

        Timer.builder("mcagents.tool.calls")
                .tag("tool", spec.name())
                .tag("outcome", failed ? "failed" : "ok")
                .register(meters)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);

        log.atLevel(failed ? org.slf4j.event.Level.WARN : org.slf4j.event.Level.DEBUG)
                .log("{} bot={} {}ms: {}", spec.name(), stringArg(arguments, "bot"),
                        elapsedNanos / 1_000_000, firstLineOf(result));

        return result;
    }

    private McpSchema.CallToolResult dispatch(ToolSpec spec, Map<String, Object> arguments, Progress progress) {
        try {
            return switch (spec.route()) {
                case LOCAL -> local.call(spec, arguments, progress);
                case RPC -> remote.call(spec, resolve(spec, arguments), arguments);
                case COMPOSE -> remote.compose(spec, resolve(spec, arguments), arguments, progress);
                case ORCHESTRATE -> orchestration.call(spec, arguments, progress);
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            return failure(e.getMessage());
        } catch (RuntimeException e) {
            /* The class name is noise to an agent; the message is the sentence. A null one has neither. */
            return failure("%s failed: %s".formatted(spec.name(), e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /**
     * Refuse a tool this bot cannot run before anything is sent, and name a kind that can. The
     * alternative is a round trip that ends in the same refusal with less to act on.
     */
    private BotSession resolve(ToolSpec spec, Map<String, Object> arguments) {
        BotSession session = bots.resolve(stringArg(arguments, "bot"));

        kindCheck(spec, session);

        /*
        A tool the bot did not report at handshake is absent, which is how one kind of bot ships a
        tool before the other does. Sending it anyway spends a round trip to be told the same
        thing, and the bot's answer arrives as "does not implement it after all", which reads like
        something changed rather than like it was never there.

        A wait is the exception: nothing of it reaches the bot, so there is nothing for the bot to
        have offered. What it polls is a tool of its own, and that one is checked when it is called.
        */
        if (spec.watches() == null && !session.supports(spec.name())) {
            throw new IllegalStateException(
                    "bot \"%s\" (kind: %s) does not implement \"%s\". It was not offered at the handshake, so this kind of bot cannot run it yet. list-bots shows what else is connected."
                            .formatted(session.name(), session.kind(), spec.name()));
        }
        return session;
    }

    /**
     * The half of the refusal a feed tool needs too. A feed only one kind of bot fills reads as
     * empty on the other kind, and "the server has not sent a sound yet" on a bot that never
     * reports one is a false negative dressed as a fact.
     */
    static void kindCheck(ToolSpec spec, BotSession session) {
        if (!spec.supportedBy(session.kind())) {
            throw new IllegalStateException(
                    "\"%s\" is not supported by bot \"%s\" (kind: %s). Bots of kind %s support it: either use one (list-bots shows each bot's kind) or join-server with that kind."
                            .formatted(spec.name(), session.name(), session.kind(),
                                    String.join(" or ", spec.kinds())));
        }
    }

    static String stringArg(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    static String textOf(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(content -> ((McpSchema.TextContent) content).text())
                .findFirst().orElse("");
    }

    static String firstLineOf(McpSchema.CallToolResult result) {
        String text = textOf(result);
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
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
