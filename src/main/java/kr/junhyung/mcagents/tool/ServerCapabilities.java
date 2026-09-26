package kr.junhyung.mcagents.tool;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import kr.junhyung.mcagents.bot.BotSession;
import kr.junhyung.mcagents.catalog.Catalog;
import kr.junhyung.mcagents.catalog.ToolSpec;
import kr.junhyung.mcagents.protocol.Messages;
import org.springframework.stereotype.Component;

/**
 * Whether the server a bot is on runs the plugin a tool drives, and whether that bot may use it.
 *
 * <p>Several tools here are a plugin's command with a session around it, and each of them used to
 * find out for itself that the plugin was missing: one read an empty tab completion, another waited
 * three seconds for {@code //pos1} to go unanswered. Two ways of learning the same fact and two
 * sentences for it, each of them a wait that the answer did not need.
 *
 * <p>The fact is cheap to come by. A server sends each player the whole command tree they are
 * allowed to use, at login, so the client already knows -- and asking the bot to complete a
 * literal reads that tree without a packet, a command or a wait. It answers both halves of the
 * question at once: a command that is missing from a player's tree is missing because the plugin
 * is not there or because that player may not run it, and asking the parent apart from the child
 * is what tells those two apart.
 *
 * <p>The verdict has three values and not two. {@code UNKNOWN} -- an azalea bot with no
 * completion tool, a bot in no world -- means the gate could not see, and a gate that cannot see
 * is not evidence of absence, so the tool goes ahead and its own detection has the last word.
 */
@Component
public class ServerCapabilities {

    /** CraftEngine's debug commands: what the custom block dictionary is learned through. */
    public static final String CRAFT_ENGINE_DEBUG = "craftengine-debug";

    /** WorldEdit or FastAsyncWorldEdit, whichever answers {@code //set}. */
    public static final String WORLD_EDIT = "worldedit";

    /**
     * How long a verdict is kept.
     *
     * <p>Nothing tells this server that a plugin was reloaded or that the bot was given a
     * permission while it stood there, so a short life is the only honest answer. The probe is one
     * call the bot answers off a tree it already holds, so the cache saves a round trip per tool
     * call rather than anything worth being wrong for.
     */
    private static final Duration KEPT = Duration.ofSeconds(60);

    /** How long the probe is given. The bot answers a literal without asking the server. */
    private static final int PROBE_MS = 2_000;

    /** Enough to hold any of these command's sub-commands; the count is never compared to anything. */
    private static final int COMPLETIONS = 64;

    public enum Verdict {
        /** The command is in this bot's tree. */
        PRESENT,
        /** Nothing completes: the plugin is not on the server, or none of it is this bot's to run. */
        ABSENT,
        /** The plugin answers, and this one command is not in this bot's tree. */
        UNPERMITTED,
        /** The gate could not see. Not evidence of anything. */
        UNKNOWN
    }

    /** A capability as this server knows how to ask about it. */
    private record Probe(String parent, String command, String wanted, String plugin, String permission) {}

    private static final Map<String, Probe> PROBES = Map.of(
            CRAFT_ENGINE_DEBUG,
            new Probe("/craftengine ", "/craftengine debug ", "get-block-internal-id", "CraftEngine",
                    "ce.command.debug.get_block_internal_id"),
            WORLD_EDIT,
            new Probe(null, "//", "set", "WorldEdit", null));

    /** What was found, and when, so it can be let go of. */
    private record Known(Verdict verdict, long at) {}

    /**
     * A verdict belongs to a player and not to a server.
     *
     * <p>A command tree is filtered per player, so an op bot and a plain one on the same address
     * disagree about the same command, and a dictionary of custom blocks -- which is the server's
     * and is shared -- keys differently for that reason.
     */
    private record Key(String address, String username, String capability) {}

    private final Map<Key, Known> known = new ConcurrentHashMap<>();
    private final Catalog catalog;
    private final RemoteTools remote;

    public ServerCapabilities(Catalog catalog, RemoteTools remote) {
        this.catalog = catalog;
        this.remote = remote;
    }

    /**
     * Refuse a tool whose plugin is not on the server the bot is on, before the bot is touched.
     *
     * <p>Only a seen absence refuses. A gate that could not look says nothing, and the tool's own
     * detection has the last word -- which is what every tool written before this gate already
     * does, and does correctly.
     */
    public void require(ToolSpec spec, BotSession bot) {
        String refused = refusal(spec, bot);

        if (refused != null) {
            throw new IllegalStateException(refused);
        }
    }

    /**
     * The reason a tool cannot run on this server, or null to go ahead.
     *
     * <p>Every capability the tool declares is asked about, and the first that refuses is the
     * answer: a tool that needs two plugins and has neither is best told about one of them.
     */
    public String refusal(ToolSpec spec, BotSession bot) {
        if (spec.requires().isEmpty()) {
            return null;
        }
        for (String capability : spec.requires()) {
            String refused = refusal(bot, capability);

            if (refused != null) {
                return refused;
            }
        }
        return null;
    }

    public String refusal(BotSession bot, String capability) {
        Probe probe = PROBES.get(capability);

        if (probe == null) {
            throw new IllegalStateException("nothing knows how to ask for \"%s\"".formatted(capability));
        }
        return switch (verdict(bot, capability, probe)) {
            case PRESENT, UNKNOWN -> null;
            case ABSENT -> "the server bot \"%s\" is on has no %s, or none of it is this bot's to run: \"%s\" completes nothing. %s"
                    .formatted(bot.name(), probe.plugin(), probe.command().trim(), remedy(probe));
            case UNPERMITTED -> "%s is on the server and \"%s\" is not this bot's to run%s."
                    .formatted(probe.plugin(), probe.command() + probe.wanted(),
                            probe.permission() == null ? "" : " (permission %s, which op has)".formatted(probe.permission()));
        };
    }

    private static String remedy(Probe probe) {
        return probe.permission() == null
                ? "A tool that drives it cannot work here."
                : "A tool that drives it cannot work here; the permission it needs is %s.".formatted(probe.permission());
    }

    private Verdict verdict(BotSession bot, String capability, Probe probe) {
        Messages.Status status = bot.status();
        Key key = status == null ? null : new Key(status.address(), status.username(), capability);
        Known cached = key == null ? null : known.get(key);

        if (cached != null && System.currentTimeMillis() - cached.at() < KEPT.toMillis()) {
            return cached.verdict();
        }
        Verdict found = probed(bot, probe);

        /* Only a seen verdict is worth keeping: an unseen one would hold the blindness for a minute. */
        if (key != null && found != Verdict.UNKNOWN) {
            known.put(key, new Known(found, System.currentTimeMillis()));
        }
        return found;
    }

    private Verdict probed(BotSession bot, Probe probe) {
        ToolSpec complete = catalog.require("complete-command");

        if (!bot.supports(complete.name())) {
            return Verdict.UNKNOWN;
        }
        List<String> completions = completions(bot, complete, probe.command());

        if (completions == null) {
            return Verdict.UNKNOWN;
        }
        if (completions.stream().anyMatch(probe.wanted()::equals)) {
            return Verdict.PRESENT;
        }
        /*
        Nothing under the command itself. Whether that is an absent plugin or one command withheld
        is only answerable by asking what the command hangs off, and a capability with nothing above
        it -- WorldEdit's // is its own root -- has no second question to ask.
        */
        if (probe.parent() == null || completions.isEmpty()) {
            return Verdict.ABSENT;
        }
        List<String> above = completions(bot, complete, probe.parent());

        return above == null || above.isEmpty() ? Verdict.ABSENT : Verdict.UNPERMITTED;
    }

    /**
     * What the bot completes after a literal, or null when it could not say.
     *
     * <p>The text always ends where a literal does. A completion asked for in the middle of an
     * argument is one the client has to ask the server for, which costs a round trip and a
     * timeout -- and the whole reason to ask this way is that a literal costs neither.
     */
    private List<String> completions(BotSession bot, ToolSpec complete, String text) {
        Map<String, Object> arguments = new LinkedHashMap<>();

        arguments.put("text", text);
        arguments.put("limit", COMPLETIONS);
        arguments.put("timeoutMs", PROBE_MS);

        Messages.Result result;

        try {
            result = remote.fetch(complete, bot, arguments).result();
        } catch (IllegalStateException unanswered) {
            return null;
        }
        if (!result.ok() || !(result.data() instanceof Map<?, ?> data)) {
            return null;
        }
        List<String> names = new java.util.ArrayList<>();

        if (data.get("completions") instanceof List<?> completions) {
            for (Object completion : completions) {
                if (completion instanceof Map<?, ?> one && one.get("name") instanceof String name) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** Forget what was known about a bot, for a session that is over or a body that moved servers. */
    public void forget(String bot) {
        known.keySet().removeIf(key -> key.username() != null && key.username().equals(bot));
    }
}
