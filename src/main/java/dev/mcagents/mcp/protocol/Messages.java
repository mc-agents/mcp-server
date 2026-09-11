package dev.mcagents.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;

/**
 * Every JSON frame the wire carries, discriminated by {@code t}.
 *
 * <p>Unknown fields are ignored so a newer bot can add one without breaking an older server;
 * an unknown {@code t} is not, because acting on a message you cannot read is worse than closing.
 */
public final class Messages {

    private Messages() {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "t")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Hello.class, name = "hello"),
        @JsonSubTypes.Type(value = Result.class, name = "result"),
        @JsonSubTypes.Type(value = Event.class, name = "event"),
        @JsonSubTypes.Type(value = Status.class, name = "status"),
        @JsonSubTypes.Type(value = Log.class, name = "log"),
        @JsonSubTypes.Type(value = Pong.class, name = "pong"),
    })
    public sealed interface FromBot {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "t")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = HelloOk.class, name = "helloOk"),
        @JsonSubTypes.Type(value = HelloErr.class, name = "helloErr"),
        @JsonSubTypes.Type(value = Connect.class, name = "connect"),
        @JsonSubTypes.Type(value = Call.class, name = "call"),
        @JsonSubTypes.Type(value = Cancel.class, name = "cancel"),
        @JsonSubTypes.Type(value = Disconnect.class, name = "disconnect"),
        @JsonSubTypes.Type(value = Shutdown.class, name = "shutdown"),
        @JsonSubTypes.Type(value = Ping.class, name = "ping"),
    })
    public sealed interface ToBot {}

    public record Capability(String tool, String argsHash) {}

    public record Hello(
            List<Integer> protocols,
            String botName,
            String kind,
            String agentVersion,
            String mcVersion,
            String catalogVersion,
            List<Capability> capabilities,
            List<String> features) implements FromBot {}

    public record RejectedTool(String tool, String reason) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HelloOk(
            int protocol,
            String sessionId,
            int heartbeatMs,
            int repeatFlushMs,
            Map<String, Object> limits,
            List<String> acceptedTools,
            List<RejectedTool> rejectedTools) implements ToBot {}

    public record HelloErr(String code, String message) implements ToBot {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Connect(
            long id,
            String host,
            int port,
            String username,
            String version,
            int spawnTimeoutMs) implements ToBot {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Call(
            long id,
            String tool,
            Map<String, Object> args,
            int deadlineMs,
            String traceId) implements ToBot {}

    public record Cancel(long id, String reason) implements ToBot {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Disconnect(long id, String reason, String quitMessage) implements ToBot {}

    public record Shutdown(String reason, int graceMs) implements ToBot {}

    public record Ping(long nonce, long ackEventSeq) implements ToBot {}

    public record Pong(long nonce, long ts, int busy) implements FromBot {}

    /**
     * @param retryable a hint only. The server never retries on its own, because a tool that half
     *                  ran has already changed the world and doing it twice is worse than failing.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Failure(
            // "class" on the wire; Java will not let the component be called that.
            @JsonProperty("class") String errorClass,
            String code,
            String message,
            boolean retryable,
            Map<String, Object> detail) {}

    /**
     * What a {@code result} says about a blob frame it was accompanied by. {@code name} and the
     * dimensions are optional: a bot that knows them saves the server from measuring, and a
     * screenshot's size is worth telling an agent that is about to look at one.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Blob(
            String id,
            String mime,
            int bytes,
            String name,
            Integer width,
            Integer height) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(
            long id,
            boolean ok,
            String text,
            Object data,
            List<Blob> blobs,
            Failure error,
            long elapsedMs) implements FromBot {}

    public record Segment(String text, String font, String color) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Event(
            long seq,
            String kind,
            String source,
            String text,
            List<Segment> segments,
            Object data,
            long ts,
            long firstTs,
            int repeats,
            boolean closed) implements FromBot {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Position(double x, double y, double z) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Status(
            String state,
            long ts,
            String address,
            String username,
            String mcVersion,
            String serverBrand,
            String gameMode,
            String dimension,
            Position position,
            Double health,
            Double food,
            String reason,
            Failure lastError) implements FromBot {}

    public record Log(String level, String message, Map<String, Object> fields) implements FromBot {}
}
