package kr.junhyung.mcagents.tool;

import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.junhyung.mcagents.bot.BotProvisioner;
import kr.junhyung.mcagents.bot.BotRegistry;
import kr.junhyung.mcagents.bot.BotSession;
import kr.junhyung.mcagents.bot.JoinFailure;
import kr.junhyung.mcagents.bot.JoinStage;
import kr.junhyung.mcagents.catalog.ToolSpec;
import kr.junhyung.mcagents.protocol.Messages;
import kr.junhyung.mcagents.render.Text;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Sending a bot into a world, taking it back out, and the tools the server drives its session for.
 *
 * <p>A bot is a process that dials in on its own and then waits. Joining it to a server is a
 * message, not a launch, which is why this costs a second or two rather than the five to twenty a
 * Minecraft client takes to boot. Starting the process is the operator's job, and when there is no
 * process by that name the failure says so in those terms instead of blaming the game server.
 *
 * <p>Every failure names the stage it reached. The server this replaces answered all of them with
 * "connection failed", which left you unable to tell a bot that never started from a server that
 * refused the login, and those have nothing to do with each other.
 *
 * <p>The route is called orchestrate because the server drives the session rather than forwarding
 * one call, and a lifecycle is only one thing that means. The region tools are the other: a run of
 * the bot's own commands, driven from here and answered from the server's feeds. They live in
 * {@link RegionTools} because what they know is WorldEdit, which has nothing to do with joining.
 */
@Component
public class Orchestration {

    private static final String READY = "ready";

    /** How long the bot is given to say it has left, once it has been told to. */
    private static final int LEAVE_TIMEOUT_MS = 10_000;

    /** How long a bot that had to be started is given to appear and introduce itself. */
    private static final int START_TIMEOUT_MS = 180_000;

    private static final int START_POLL_MS = 500;

    /* A name or an IPv4 address and a port. An IPv6 address has colons of its own and is left whole. */
    private static final Pattern HOST_WITH_PORT = Pattern.compile("([^:\\[\\]]+):(\\d{1,5})");

    /*
    How long one join-server call waits for a bot it had to start. A fabric client takes about a
    minute to link, longer than an MCP client waits for a call: the first join was reported as timed
    out while the bot went on to link, and the retry found it already there. So a call gives up well
    inside that, says the bot is still starting, and the next call picks the same bot up.
    */
    private static final Duration JOIN_PATIENCE = Duration.ofSeconds(40);

    private final BotRegistry bots;
    private final BotProvisioner provisioner;
    private final RegionTools regions;
    private final RegionSurvey survey;
    private final CustomBlocks customBlocks;
    private final Photographs photographs;
    private final ServerCapabilities capabilities;
    private final Duration patience;

    @Autowired
    public Orchestration(BotRegistry bots, BotProvisioner provisioner, RegionTools regions, RegionSurvey survey,
            CustomBlocks customBlocks, Photographs photographs,
            ServerCapabilities capabilities) {
        this(bots, provisioner, regions, survey, customBlocks, photographs, capabilities, JOIN_PATIENCE);
    }

    Orchestration(BotRegistry bots, BotProvisioner provisioner, RegionTools regions, RegionSurvey survey,
            CustomBlocks customBlocks, Photographs photographs,
            ServerCapabilities capabilities, Duration patience) {
        this.patience = patience;
        this.bots = bots;
        this.provisioner = provisioner;
        this.regions = regions;
        this.survey = survey;
        this.customBlocks = customBlocks;
        this.photographs = photographs;
        this.capabilities = capabilities;
    }

    public McpSchema.CallToolResult call(ToolSpec spec, Map<String, Object> arguments) {
        return call(spec, arguments, Progress.NONE);
    }

    public McpSchema.CallToolResult call(ToolSpec spec, Map<String, Object> given, Progress progress) {
        /* Nothing here crosses the wire as a call, so the catalogue's bounds are the server's to hold. */
        Map<String, Object> arguments = Normaliser.bound(spec, given);

        try {
            return switch (spec.name()) {
                case "join-server" -> join(spec, arguments, progress);
                case "leave-server" -> leave(arguments);
                case "restart-bot" -> restart(spec, arguments);
                /* Not the bot's lifecycle but its session: a sequence of commands sent as the bot. */
                case "build-region", "verify-region" -> regions.call(spec, arguments, progress);
                case "write-region" -> survey.write(spec, resolve(spec, arguments), arguments, progress);
                case "learn-custom-blocks" -> customBlocks.learn(spec, resolve(spec, arguments), arguments, progress);
                case "photograph-region" -> photographs.take(spec, resolve(spec, arguments), arguments, progress);
                default -> ToolDispatcher.failure("%s is not an orchestration tool".formatted(spec.name()));
            };
        } catch (JoinFailure | StillStarting e) {
            return ToolDispatcher.failure(e.getMessage());
        }
    }

    /** The bot a session tool drives, refused first when it cannot run what the tool sends. */
    private BotSession resolve(ToolSpec spec, Map<String, Object> arguments) {
        BotSession bot = bots.resolve(ToolDispatcher.stringArg(arguments, "bot"));

        ToolDispatcher.kindCheck(spec, bot);
        capabilities.require(spec, bot);

        return bot;
    }

    private McpSchema.CallToolResult join(ToolSpec spec, Map<String, Object> arguments, Progress progress) {
        String name = botName(arguments);
        String[] address = hostAndPort(required(arguments, "host"), arguments.get("port"));
        String host = address[0];
        int port = Integer.parseInt(address[1]);
        String username = ToolDispatcher.stringArg(arguments, "username");
        String version = ToolDispatcher.stringArg(arguments, "version");
        String where = "%s:%d".formatted(host, port);

        BotSession bot = linkedOrStarted(name, arguments, progress);
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

        /*
        The spawn's budget is the caller's: a server that holds a player in configuration for a
        resource pack needs more than the default, and a case that wants the timeout needs less.
        */
        int timeoutMs = intArg(arguments, "timeoutMs", spec.defaultDeadlineMs());
        connect(bot, host, port, username == null ? name : username, version, timeoutMs, where);

        return ToolDispatcher.text(describe(bot));
    }

    /**
     * Leave the world, keep the process.
     *
     * <p>The bot goes back to being linked and idle, ready for the next join, because the process
     * is the part that took time to start. Ending it is the operator's decision, not a tool call's.
     */
    private McpSchema.CallToolResult leave(Map<String, Object> arguments) {
        String requested = ToolDispatcher.stringArg(arguments, "bot");
        BotSession bot;

        try {
            bot = bots.resolve(requested);
        } catch (IllegalArgumentException absent) {
            /*
            A bot this server asked for that never dialled in -- a pod that cannot start, an image
            that does not exist -- has no session to leave, and its MinecraftBot is still holding a
            pod. Giving that back is the whole of leaving it.
            */
            if (requested != null && released(requested)) {
                return ToolDispatcher.text(
                        "Bot \"%s\" never linked, and the bot it was being started on was given back."
                                .formatted(requested));
            }
            throw absent;
        }

        Messages.Status before = bot.status();

        /*
        Not in a world is still a bot this server may have started: a join or a restart that failed
        leaves one linked and idle, and answering "nothing to leave" left its pod running for good.
        */
        if (before == null || !READY.equals(before.state())) {
            if (released(bot.name())) {
                return ToolDispatcher.text(
                        "Bot \"%s\" is not in a world, and the bot it was running on was given back."
                                .formatted(bot.name()));
            }
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
        if (released(bot.name())) {
            return ToolDispatcher.text(
                    "Bot \"%s\" left %s, and the bot it was running on was given back."
                            .formatted(bot.name(), before.address()));
        }
        return ToolDispatcher.text(
                "Bot \"%s\" left %s and is linked and idle.".formatted(bot.name(), before.address()));
    }

    /**
     * Give back the bot's MinecraftBot, when there is a cluster and this server is the one that
     * asked for it, and forget its session at once.
     *
     * <p>The pod takes a moment to go, and its link stayed open for that moment: a join-server
     * sent right after a leave-server found the session still linked, put the bot back in the
     * world, and answered "on the server" for a bot whose pod was being killed and was gone
     * seconds later. A given-back bot is not one to reuse, so the session goes with the object.
     */
    private boolean released(String name) {
        if (!provisioner.available() || !provisioner.release(name)) {
            return false;
        }
        bots.giveBack(name);
        bots.remove(name, "the bot was given back");
        return true;
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

        /* The reason is the server's: a kick message, a login refusal, whatever the bot was told. */
        if (!joined.ok()) {
            throw new JoinFailure(stageOf(joined),
                    Trust.mark("bot \"%s\" could not join %s: %s".formatted(bot.name(), where, joined.text())));
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
    private BotSession linkedOrStarted(String name, Map<String, Object> arguments, Progress progress) {
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

        boolean created = provisioner.request(name, kind, mcVersion, ToolDispatcher.stringArg(arguments, "owner"));

        try {
            return awaitLink(name, kind, progress);
        } catch (JoinFailure never) {
            /*
            A bot this call asked for and that never linked cannot be reached by leave-server under a
            session, and its pod would be left starting, failing or waiting. One asked for by an
            earlier call is that call's, and is left for it.
            */
            if (created) {
                provisioner.release(name);
            }
            throw never;
        }
    }

    /**
     * Wait for a bot that was just asked for to dial in.
     *
     * <p>Polling the registry rather than watching the pod: the link is the thing that matters and
     * it is the thing the server can see. A pod that is Running but has not linked is not usable,
     * and an operator status that says Running would be a more encouraging lie.
     */
    private BotSession awaitLink(String name, String kind, Progress progress) {
        long started = System.currentTimeMillis();
        long deadline = started + patience.toMillis();

        while (System.currentTimeMillis() < deadline) {
            try {
                return bots.resolve(name);
            } catch (IllegalArgumentException notYet) {
                String failed = provisioner.failure(name);

                if (failed != null) {
                    throw new JoinFailure(JoinStage.LINK,
                            "the bot named \"%s\" could not be started: %s".formatted(name, failed));
                }
                Duration asked = provisioner.age(name);
                progress.report("waiting for the %s bot named \"%s\" to dial in%s".formatted(kind, name,
                        asked == null ? "" : " (asked for %ds ago)".formatted(asked.toSeconds())),
                        System.currentTimeMillis() - started, patience.toMillis());
                try {
                    TimeUnit.MILLISECONDS.sleep(START_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new JoinFailure(JoinStage.LINK, "the wait was interrupted");
                }
            }
        }

        /* Measured from when the bot was asked for, so a call that picks a booting bot up does not start the clock again. */
        Duration age = provisioner.age(name);
        if (age != null && age.toMillis() < START_TIMEOUT_MS) {
            throw new StillStarting(
                    "a %s bot named \"%s\" is still starting (asked for %ds ago; a fabric client takes about a minute to link). Call join-server again with the same arguments: it waits for this bot rather than starting another."
                            .formatted(kind, name, age.toSeconds()));
        }
        throw new JoinFailure(JoinStage.LINK,
                "a %s bot named \"%s\" was asked for but never dialled in within %dms. Look at the MinecraftBot: \"kubectl describe minecraftbot %s\" says whether the pod started and what stopped it."
                        .formatted(kind, name, START_TIMEOUT_MS, name));
    }

    /** Not a failure of the bot, so nothing is given back: the next call waits for the same one. */
    private static final class StillStarting extends RuntimeException {

        private static final long serialVersionUID = 1L;

        StillStarting(String message) {
            super(message);
        }
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

    /** The brand is the server's own string, and a plugin can make it anything, so the line is marked. */
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
        body.append('.');
        return status.serverBrand() == null ? body.toString() : Trust.mark(body.toString());
    }

    /*
    join-server called the bot "name" while every other tool calls it "bot", so a caller that used
    one word everywhere failed schema validation on one side or the other. Both are taken here.
    */
    private static String botName(Map<String, Object> arguments) {
        String bot = ToolDispatcher.stringArg(arguments, "bot");
        String name = ToolDispatcher.stringArg(arguments, "name");
        if (bot != null && name != null && !bot.equals(name)) {
            throw new IllegalArgumentException("bot says \"%s\" and name says \"%s\"; give one of them".formatted(bot, name));
        }
        if (bot == null && name == null) {
            throw new IllegalArgumentException("\"bot\" is required");
        }
        return bot != null ? bot : name;
    }

    private static String required(Map<String, Object> arguments, String name) {
        String value = ToolDispatcher.stringArg(arguments, name);

        if (value == null) {
            throw new IllegalArgumentException("\"%s\" is required".formatted(name));
        }
        return value;
    }

    /*
    A host written with its port. The check for a bot already there compared the whole string and
    accepted it, while a bot that had to be started was handed "host:port" as the host and failed
    with "Host has a port", so the same arguments worked or did not depending on what was running.
    */
    static String[] hostAndPort(String host, Object port) {
        Matcher written = HOST_WITH_PORT.matcher(host);
        if (!written.matches()) {
            return new String[] {host, String.valueOf(port instanceof Number number ? number.intValue() : 25_565)};
        }
        int named = Integer.parseInt(written.group(2));
        if (port instanceof Number number && number.intValue() != named) {
            throw new IllegalArgumentException("host names port %d and port says %d; give one of them"
                    .formatted(named, number.intValue()));
        }
        return new String[] {written.group(1), String.valueOf(named)};
    }

    private static int intArg(Map<String, Object> arguments, String name, int fallback) {
        Object value = arguments == null ? null : arguments.get(name);
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
