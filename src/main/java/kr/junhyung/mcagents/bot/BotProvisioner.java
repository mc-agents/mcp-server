package kr.junhyung.mcagents.bot;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
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

    private static final CustomResourceDefinitionContext BOTS = new CustomResourceDefinitionContext.Builder()
            .withGroup("mc-agents.dev")
            .withVersion("v1alpha1")
            .withPlural("minecraftbots")
            .withScope("Namespaced")
            .withKind("MinecraftBot")
            .build();

    private final KubernetesClient client;
    private final String namespace;
    private final String mcpHost;
    private final int mcpPort;

    public BotProvisioner(KubernetesClient client, String namespace, String mcpHost, int mcpPort) {
        this.client = client;
        this.namespace = namespace;
        this.mcpHost = mcpHost;
        this.mcpPort = mcpPort;
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
        GenericKubernetesResource existing = client.genericKubernetesResources(BOTS)
                .inNamespace(namespace).withName(name).get();

        if (existing != null) {
            log.info("bot \"{}\" is already declared; waiting for it rather than making another", name);
            return false;
        }

        try {
            client.genericKubernetesResources(BOTS).inNamespace(namespace)
                    .resource(declare(name, kind, minecraftVersion, owner)).create();
        } catch (KubernetesClientException e) {
            throw new JoinFailure(JoinStage.LINK,
                    "could not ask for a bot named \"%s\": %s".formatted(name, reason(e)));
        }

        log.info("asked for a {} bot named \"{}\" on Minecraft {}", kind, name, minecraftVersion);
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
        GenericKubernetesResource bot = client.genericKubernetesResources(BOTS)
                .inNamespace(namespace).withName(name).get();

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
     * Take the bot away again. Only ever a bot this server asked for: the label is what says so,
     * and one someone else declared with kubectl outlives any tool call.
     */
    public boolean release(String name) {
        GenericKubernetesResource existing = client.genericKubernetesResources(BOTS)
                .inNamespace(namespace).withName(name).get();

        if (existing == null || !"mcp-server".equals(
                existing.getMetadata().getLabels().get("mc-agents.dev/requested-by"))) {
            return false;
        }
        client.genericKubernetesResources(BOTS).inNamespace(namespace).withName(name).delete();
        log.info("gave back the bot named \"{}\"", name);
        return true;
    }

    private GenericKubernetesResource declare(String name, String kind, String minecraftVersion,
            String owner) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("kind", kind);
        spec.put("minecraftVersion", minecraftVersion);
        spec.put("server", Map.of("host", mcpHost, "port", mcpPort));

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("app.kubernetes.io/managed-by", "mc-agents-mcp-server");
        /* What release() checks. A bot somebody declared by hand is not this server's to delete. */
        labels.put("mc-agents.dev/requested-by", "mcp-server");

        Map<String, String> annotations = owner == null ? Map.of()
                : Map.of("mc-agents.dev/owner", owner);

        return new GenericKubernetesResourceBuilder()
                .withApiVersion("mc-agents.dev/v1alpha1")
                .withKind("MinecraftBot")
                .withNewMetadata()
                .withName(name)
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
