package kr.junhyung.mcagents.tool;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kr.junhyung.mcagents.bot.BotProvisioner;
import kr.junhyung.mcagents.bot.BotRegistry;
import kr.junhyung.mcagents.bot.BotSession;
import kr.junhyung.mcagents.bot.JoinFailure;
import kr.junhyung.mcagents.bot.JoinStage;
import kr.junhyung.mcagents.catalog.ToolSpec;
import kr.junhyung.mcagents.protocol.Messages;
import kr.junhyung.mcagents.render.Text;
import org.springframework.stereotype.Component;

/**
 * Sending a bot into a world, and taking it back out.
 *
 * <p>A bot is a process that dials in on its own and then waits. Joining it to a server is a
 * message, not a launch, which is why this costs a second or two rather than the five to twenty a
 * Minecraft client takes to boot. Starting the process is the operator's job, and when there is no
 * process by that name the failure says so in those terms instead of blaming the game server.
 *
 * <p>Every failure names the stage it reached. The server this replaces answered all of them with
 * "connection failed", which left you unable to tell a bot that never started from a server that
 * refused the login, and those have nothing to do with each other.
 */
@Component
public class Orchestration {

    private static final String READY = "ready";

    /** How long the bot is given to say it has left, once it has been told to. */
    private static final int LEAVE_TIMEOUT_MS = 10_000;

    /** How long a bot that had to be started is given to appear and introduce itself. */
    private static final int START_TIMEOUT_MS = 180_000;

    private static final int START_POLL_MS = 500;

    private final BotRegistry bots;
    private final BotProvisioner provisioner;

    public Orchestration(BotRegistry bots, BotProvisioner provisioner) {
        this.bots = bots;
        this.provisioner = provisioner;
    }

    public McpSchema.CallToolResult call(ToolSpec spec, Map<String, Object> arguments) {
        try {
            return switch (spec.name()) {
                case "join-server" -> join(spec, arguments);
                case "leave-server" -> leave(arguments);
                case "restart-bot" -> restart(spec, arguments);
                default -> ToolDispatcher.failure("%s is not an orchestration tool".formatted(spec.name()));
            };
        } catch (JoinFailure e) {
            return ToolDispatcher.failure(e.getMessage());
        }
    }

    private McpSchema.CallToolResult join(ToolSpec spec, Map<String, Object> arguments) {
        String name = required(arguments, "name");
        String host = required(arguments, "host");
        int port = intArg(arguments, "port", 25_565);
        String username = ToolDispatcher.stringArg(arguments, "username");
        String version = ToolDispatcher.stringArg(arguments, "version");
        String where = "%s:%d".formatted(host, port);

        BotSession bot = linkedOrStarted(name, arguments);
        Messages.Status current = bot.status();

        if (current != null && READY.equals(current.state())) {
            if (where.equals(current.address())) {
                return ToolDispatcher.text(
                        "Bot \"%s\" is already on %s as %s. Reuse it; there is nothing to join."
                                .formatted(name, where, current.username()));
            }
            throw new JoinFailure(JoinStage.LOGIN,
                    "bot \"%s\" is already on %s. Use switch-server to move it there, or leave-server first."
                            .formatted(name, current.address()));
        }

        connect(bot, host, port, username == null ? name : username, version, spec.defaultDeadlineMs(), where);

        return ToolDispatcher.text(describe(bot));
    }

    /**
     * Leave the world, keep the process.
     *
     * <p>The bot goes back to being linked and idle, ready for the next join, because the process
     * is the part that took time to start. Ending it is the operator's decision, not a tool call's.
     */
    private McpSchema.CallToolResult leave(Map<String, Object> arguments) {
        BotSession bot = bots.resolve(ToolDispatcher.stringArg(arguments, "bot"));
        Messages.Status before = bot.status();

        if (before == null || !READY.equals(before.state())) {
            return ToolDispatcher.text(
                    "Bot \"%s\" is not in a world, so there was nothing to leave.".formatted(bot.name()));
        }

        Messages.Result left = await(bot,
                bot.link().request("disconnect", LEAVE_TIMEOUT_MS,
                        id -> new Messages.Disconnect(id, "leave-server", null)),
                LEAVE_TIMEOUT_MS);

        if (!left.ok()) {
            return ToolDispatcher.failure(
                    "bot \"%s\" was told to leave %s and could not: %s"
                            .formatted(bot.name(), before.address(), left.text()));
        }

        /*
        A bot this server asked the operator for is given back; one somebody declared by hand, or
        started on a laptop, is not this call's to end. It goes idle and waits for the next join.
        */
        if (provisioner.available() && provisioner.release(bot.name())) {
            return ToolDispatcher.text(
                    "Bot \"%s\" left %s, and the bot it was running on was given back."
                            .formatted(bot.name(), before.address()));
        }
        return ToolDispatcher.text(
                "Bot \"%s\" left %s and is linked and idle.".formatted(bot.name(), before.address()));
    }

    /**
     * Leave and rejoin the same server under the same name.
     *
     * <p>This is the development loop's tool. After a plugin is redeployed the bot is still
     * connected and still holding whatever the previous build gave it, and a stale inventory or a
     * stale scoreboard looks exactly like a bug in the new build.
     */
    private McpSchema.CallToolResult restart(ToolSpec spec, Map<String, Object> arguments) {
        BotSession bot = bots.resolve(ToolDispatcher.stringArg(arguments, "bot"));
        Messages.Status before = bot.status();

        if (before == null || before.address() == null) {
            throw new JoinFailure(JoinStage.LOGIN,
                    "bot \"%s\" has not been in a world, so there is nowhere to put it back. Use join-server."
                            .formatted(bot.name()));
        }

        String address = before.address();
        int colon = address.lastIndexOf(':');
        String host = colon < 0 ? address : address.substring(0, colon);
        int port = colon < 0 ? 25_565 : Integer.parseInt(address.substring(colon + 1));

        if (READY.equals(before.state())) {
            await(bot, bot.link().request("disconnect", LEAVE_TIMEOUT_MS,
                    id -> new Messages.Disconnect(id, "restart-bot", null)), LEAVE_TIMEOUT_MS);
        }

        connect(bot, host, port, before.username(), before.mcVersion(), spec.defaultDeadlineMs(), address);

        return ToolDispatcher.text("Restarted. " + describe(bot));
    }

    /**
     * Send the bot into a world and wait for the answer that says it spawned.
     *
     * <p>{@code connect} answers with a {@code result} for the same reason a tool call does: one
     * answer per id, whatever happens. Waiting on the status stream instead would leave the server
     * guessing whether silence meant a slow login or a bot that had stopped.
     */
    private void connect(BotSession bot, String host, int port, String username, String version,
            int timeoutMs, String where) {
        bot.touch();

        Messages.Result joined = await(bot,
                bot.link().request("connect", timeoutMs,
                        id -> new Messages.Connect(id, host, port, username, version, timeoutMs)),
                timeoutMs);

        if (!joined.ok()) {
            throw new JoinFailure(stageOf(joined),
                    "bot \"%s\" could not join %s: %s".formatted(bot.name(), where, joined.text()));
        }
        if (bot.status() == null || !READY.equals(bot.status().state())) {
            throw new JoinFailure(JoinStage.SPAWN,
                    "bot \"%s\" reported joining %s but never said it was ready. get-bot-status has what it last said."
                            .formatted(bot.name(), where));
        }
    }

    /**
     * Which half of the join failed. The bot answered, so it is running and linked; what is left to
     * distinguish is a server that would not take it from a world that would not put it anywhere.
     *
     * <p>An unknown code reads as a login problem, which is where a join fails most of the time,
     * so a bot may add codes without this having to learn them first.
     */
    private static JoinStage stageOf(Messages.Result failed) {
        String code = failed.error() == null || failed.error().code() == null ? "" : failed.error().code();

        return "JOIN_FAILED_SPAWN".equals(code) ? JoinStage.SPAWN : JoinStage.LOGIN;
    }

    /**
     * The bot to send somewhere, started first if it is not running.
     *
     * <p>A bot already linked is used as it is, whoever started it. Otherwise the operator is
     * asked for one and this waits for it to dial in, which is a pod start rather than a client
     * boot: the image is already pulled and the process is a second or two.
     *
     * <p>With no cluster there is nothing to ask, and saying that is the whole value of the LINK
     * stage. The fix is then in whoever should have started the bot, not on the game server.
     */
    private BotSession linkedOrStarted(String name, Map<String, Object> arguments) {
        BotRegistry.requireValidName(name);

        try {
            return bots.resolve(name);
        } catch (IllegalArgumentException absent) {
            // Not linked. Fall through and start one.
        }

        if (!provisioner.available()) {
            throw new JoinFailure(JoinStage.LINK,
                    "no bot named \"%s\" has linked, and there is no cluster to start one in. Run a bot with BOT_NAME=%s and MCP_SERVER_HOST pointing here; list-bots shows what has linked."
                            .formatted(name, name));
        }

        String kind = orDefault(ToolDispatcher.stringArg(arguments, "kind"), "fabric");
        String mcVersion = orDefault(ToolDispatcher.stringArg(arguments, "minecraftVersion"), "26.1.2");

        provisioner.request(name, kind, mcVersion, ToolDispatcher.stringArg(arguments, "owner"));

        return awaitLink(name, kind);
    }

    /**
     * Wait for a bot that was just asked for to dial in.
     *
     * <p>Polling the registry rather than watching the pod: the link is the thing that matters and
     * it is the thing the server can see. A pod that is Running but has not linked is not usable,
     * and an operator status that says Running would be a more encouraging lie.
     */
    private BotSession awaitLink(String name, String kind) {
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            try {
                return bots.resolve(name);
            } catch (IllegalArgumentException notYet) {
                String failed = provisioner.failure(name);

                if (failed != null) {
                    throw new JoinFailure(JoinStage.LINK,
                            "the bot named \"%s\" could not be started: %s".formatted(name, failed));
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(START_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new JoinFailure(JoinStage.LINK, "the wait was interrupted");
                }
            }
        }

        throw new JoinFailure(JoinStage.LINK,
                "a %s bot named \"%s\" was asked for but never dialled in within %dms. Look at the MinecraftBot: \"kubectl describe minecraftbot %s\" says whether the pod started and what stopped it."
                        .formatted(kind, name, START_TIMEOUT_MS, name));
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private Messages.Result await(BotSession bot, CompletableFuture<Messages.Result> answer, int timeoutMs) {
        try {
            return answer.get(timeoutMs + 5_000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JoinFailure(JoinStage.LINK, "the wait was interrupted");
        } catch (TimeoutException e) {
            throw new JoinFailure(JoinStage.LINK,
                    "bot \"%s\" did not answer within %dms, which means the link is stalled rather than the join being slow."
                            .formatted(bot.name(), timeoutMs));
        } catch (ExecutionException e) {
            throw new JoinFailure(JoinStage.LINK,
                    "the link to bot \"%s\" failed: %s".formatted(bot.name(), e.getCause()));
        }
    }

    private static String describe(BotSession bot) {
        Messages.Status status = bot.status();
        StringBuilder body = new StringBuilder("Bot \"%s\" is on %s as %s"
                .formatted(bot.name(), status.address(), status.username()));

        if (status.serverBrand() != null) {
            body.append(" (").append(status.serverBrand());
            if (status.mcVersion() != null) {
                body.append(", ").append(status.mcVersion());
            }
            body.append(')');
        }
        if (status.position() != null) {
            body.append(", spawned at ").append(
                    Text.block(status.position().x(), status.position().y(), status.position().z()));
        }
        if (status.gameMode() != null) {
            body.append(" in ").append(status.gameMode()).append(" mode");
        }
        return body.append('.').toString();
    }

    private static String required(Map<String, Object> arguments, String name) {
        String value = ToolDispatcher.stringArg(arguments, name);

        if (value == null) {
            throw new IllegalArgumentException("\"%s\" is required".formatted(name));
        }
        return value;
    }

    private static int intArg(Map<String, Object> arguments, String name, int fallback) {
        Object value = arguments == null ? null : arguments.get(name);
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
