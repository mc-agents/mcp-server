package kr.junhyung.mcagents.bot;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starting a bot that is not running.
 *
 * <p>The operator owns bot pods; this asks it for one by creating a {@code MinecraftBot}, and then
 * gets out of the way. Nothing here watches a pod, chooses an image, or knows a pod address: a bot
 * dials in on its own, and the link is what says it arrived.
 *
 * <p>Outside a cluster there is nothing to ask. {@link #available()} says so, and {@code
 * join-server} tells the caller a bot has to be started by hand rather than reporting a Kubernetes
 * error at somebody who is running this on a laptop.
 */
public class BotProvisioner {

    private static final Logger log = LoggerFactory.getLogger(BotProvisioner.class);

    private static final String GROUP = "mc-agents.junhyung.cloud";

    private static final String REQUESTED_BY = GROUP + "/requested-by";

    /** How long a given-back object is waited for before the one asked for is made; a pod stops within it. */
    private static final int GONE_TIMEOUT_MS = 45_000;

    private static final CustomResourceDefinitionContext BOTS = new CustomResourceDefinitionContext.Builder()
            .withGroup(GROUP)
            .withVersion("v1alpha1")
            .withPlural("minecraftbots")
            .withScope("Namespaced")
            .withKind("MinecraftBot")
            .build();

    private final KubernetesClient client;
    private final String namespace;
    private final String mcpHost;
    private final int mcpPort;
    private final Profile profile;
    private final String linkSecret;

    /**
     * The profile a bot takes its image and pod settings from, written into every MinecraftBot this
     * asks for. Null leaves the choice to the operator: the namespace's default, then the cluster's.
     */
    public record Profile(String kind, String name) {
    }

    /**
     * @param linkSecret the Secret holding the link token this server's bots have to present, in the
     *                   namespace bots are created in. Null when the server was given none: the bot
     *                   is then declared without one, and a server that requires a token would
     *                   refuse it, which is the operator's to prevent by handing out both
     */
    public BotProvisioner(KubernetesClient client, String namespace, String mcpHost, int mcpPort, Profile profile,
            String linkSecret) {
        this.client = client;
        this.namespace = namespace;
        this.mcpHost = mcpHost;
        this.mcpPort = mcpPort;
        this.profile = profile;
        this.linkSecret = linkSecret;
    }

    /** Whether there is a cluster with the operator's CRD installed. Checked once, at startup. */
    public boolean available() {
        return client != null;
    }

    /**
     * Ask for a bot, or leave the one that is already asked for alone.
     *
     * @return true when this call created it, false when it was already there
     */
    public boolean request(String name, String kind, String minecraftVersion, String owner) {
        GenericKubernetesResource existing = awaitGone(find(name));
        if (existing != null && !dialsElsewhere(existing)) {
            log.info("bot \"{}\" is already declared; waiting for it rather than making another", name);
            return false;
        }
        if (existing != null) {
            /*
            Declared against a link address this server no longer answers on: the operator moved the
            bot port to a Service of its own, or the server was renamed. The pod it makes from that
            object dials the old address for ever, and waiting for it never ends. Only a bot this
            server asked for is replaced; one declared by hand says where it dials on purpose.
            */
            if (!release(name)) {
                throw new JoinFailure(JoinStage.LINK,
                        "bot \"%s\" is declared to dial %s, which is not this server (%s), and it was not declared here"
                                .formatted(name, dialled(existing), address()));
            }
            log.info("bot \"{}\" was declared to dial {}, not this server ({}); asking for it again", name,
                    dialled(existing), address());
        }

        String objectName;
        try {
            objectName = BotResourceName.of(name);
        } catch (IllegalArgumentException refused) {
            throw new JoinFailure(JoinStage.LINK, refused.getMessage());
        }

        try {
            client.genericKubernetesResources(BOTS).inNamespace(namespace)
                    .resource(declare(objectName, name, kind, minecraftVersion, owner)).create();
        } catch (KubernetesClientException e) {
            /*
            The object's name is taken by a bot of another name. Only a name declared by hand can do
            that -- a derived one carries a hash of the bot name -- and reusing it would start the
            other bot's pod for this one.
            */
            if (e.getCode() == 409) {
                throw new JoinFailure(JoinStage.LINK,
                        "could not ask for a bot named \"%s\": the MinecraftBot \"%s\" already exists for another bot"
                                .formatted(name, objectName));
            }
            throw new JoinFailure(JoinStage.LINK,
                    "could not ask for a bot named \"%s\": %s".formatted(name, reason(e)));
        }

        log.info("asked for a {} bot named \"{}\" on Minecraft {}, as MinecraftBot \"{}\"",
                kind, name, minecraftVersion, objectName);
        return true;
    }

    /**
     * What the operator last said about a bot, or null when it has said nothing yet.
     *
     * <p>Waiting for a bot to dial in is waiting on the link, not on this. This is here so a bot
     * that will never dial in -- a pod that cannot start, an image that does not exist -- fails
     * the join in a second with the operator's own sentence, instead of running out a three minute
     * timeout and reporting that nothing happened.
     */
    public String failure(String name) {
        GenericKubernetesResource bot = find(name);

        if (bot == null) {
            return "the MinecraftBot is gone";
        }

        Object status = bot.getAdditionalProperties().get("status");

        if (!(status instanceof Map<?, ?> fields) || !"Failed".equals(fields.get("phase"))) {
            return null;
        }
        Object said = fields.get("lastError");

        return said == null ? "the operator reported it failed and did not say why" : said.toString();
    }

    /**
     * How long ago the object that stands for a bot was created, or null when there is none. What
     * a join that has run out of patience asks before calling a bot that is still booting lost.
     */
    public Duration age(String name) {
        GenericKubernetesResource bot = find(name);

        if (bot == null || bot.getMetadata().getCreationTimestamp() == null) {
            return null;
        }
        return Duration.between(Instant.parse(bot.getMetadata().getCreationTimestamp()), Instant.now());
    }

    /**
     * Take the bot away again. Only ever a bot this server asked for: the label is what says so,
     * and one someone else declared with kubectl outlives any tool call.
     */
    public boolean release(String name) {
        GenericKubernetesResource existing = find(name);

        if (existing == null || existing.getMetadata().getLabels() == null || !"mcp-server".equals(
                existing.getMetadata().getLabels().get(REQUESTED_BY))) {
            return false;
        }
        client.genericKubernetesResources(BOTS).inNamespace(namespace).resource(existing).delete();
        log.info("gave back the bot named \"{}\" (MinecraftBot \"{}\")", name, existing.getMetadata().getName());
        return true;
    }

    /**
     * An object on its way out, waited for, so the one asked for next is not the one being
     * deleted. A join right after a leave found the given-back object still there, and waited on
     * it as if it were the bot it had asked for, which it never became.
     */
    private GenericKubernetesResource awaitGone(GenericKubernetesResource existing) {
        long deadline = System.currentTimeMillis() + GONE_TIMEOUT_MS;
        GenericKubernetesResource seen = existing;

        while (seen != null && seen.getMetadata().getDeletionTimestamp() != null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return seen;
            }
            seen = find(BotResourceName.declared(seen.getMetadata().getName(), specBotName(seen)));
        }
        return seen;
    }

    /**
     * The object that stands for a bot, found by the bot name it declares rather than by its own
     * name, which a bot name only sometimes is. A handful of objects per namespace, so listing them
     * costs nothing next to the join it serves.
     */
    private GenericKubernetesResource find(String name) {
        for (GenericKubernetesResource bot : client.genericKubernetesResources(BOTS).inNamespace(namespace).list().getItems()) {
            if (name.equals(BotResourceName.declared(bot.getMetadata().getName(), specBotName(bot)))) {
                return bot;
            }
        }
        return null;
    }

    /** Whether the object names a link address other than this server's. */
    private boolean dialsElsewhere(GenericKubernetesResource bot) {
        return !address().equals(dialled(bot));
    }

    private static String dialled(GenericKubernetesResource bot) {
        if (bot.getAdditionalProperties().get("spec") instanceof Map<?, ?> spec
                && spec.get("server") instanceof Map<?, ?> server) {
            return server.get("host") + ":" + server.get("port");
        }
        return "nowhere";
    }

    private String address() {
        return mcpHost + ":" + mcpPort;
    }

    private static String specBotName(GenericKubernetesResource bot) {
        return bot.getAdditionalProperties().get("spec") instanceof Map<?, ?> spec
                && spec.get("botName") instanceof String botName ? botName : null;
    }

    /** The object join-server asks the operator for. Package-private so a test can read what it says without a cluster. */
    GenericKubernetesResource declare(String objectName, String botName, String kind,
            String minecraftVersion, String owner) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("kind", kind);
        spec.put("minecraftVersion", minecraftVersion);
        spec.put("server", Map.of("host", mcpHost, "port", mcpPort));
        /* The name the pod dials in with, when the object's own name cannot be it. */
        String dialled = BotResourceName.specBotName(botName);
        if (dialled != null) {
            spec.put("botName", dialled);
        }
        if (profile != null) {
            spec.put("profileRef", Map.of("kind", profile.kind(), "name", profile.name()));
        }
        /* The key is the operator's default; naming it keeps a bot declared by hand and one declared here alike. */
        if (linkSecret != null) {
            spec.put("linkTokenSecretRef", Map.of("name", linkSecret, "key", "token"));
        }

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("app.kubernetes.io/managed-by", "mc-agents-mcp-server");
        /* What release() checks. A bot somebody declared by hand is not this server's to delete. */
        labels.put(REQUESTED_BY, "mcp-server");

        Map<String, String> annotations = owner == null ? Map.of()
                : Map.of(GROUP + "/owner", owner);

        return new GenericKubernetesResourceBuilder()
                .withApiVersion(GROUP + "/v1alpha1")
                .withKind("MinecraftBot")
                .withNewMetadata()
                .withName(objectName)
                .withNamespace(namespace)
                .withLabels(labels)
                .withAnnotations(annotations)
                .endMetadata()
                .withAdditionalProperties(Map.of("spec", spec))
                .build();
    }

    /** A Kubernetes error message is a wall of JSON; the status line is the part worth reading. */
    private static String reason(KubernetesClientException e) {
        if (e.getStatus() != null && e.getStatus().getMessage() != null) {
            return e.getStatus().getMessage();
        }
        return e.getMessage();
    }
}
