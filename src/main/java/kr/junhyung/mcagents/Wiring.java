package kr.junhyung.mcagents;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import kr.junhyung.mcagents.bot.BotLinkServer;
import kr.junhyung.mcagents.bot.BotProvisioner;
import jakarta.servlet.Filter;
import kr.junhyung.mcagents.http.BearerTokenFilter;
import kr.junhyung.mcagents.bot.BotRegistry;
import kr.junhyung.mcagents.catalog.Catalog;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the server is made of.
 *
 * <p>The wire mapper is deliberately separate from the one Spring uses for HTTP. A bot may be
 * newer than the server and send a field this version has never heard of; ignoring it is how a
 * rolling update stays survivable. The HTTP side has no such requirement and should keep failing
 * loudly on a request it cannot read.
 */
@Configuration
public class Wiring {

    private static final Logger log = LoggerFactory.getLogger(Wiring.class);

    @Bean
    public Catalog catalog() {
        return Catalog.load();
    }

    @Bean
    public BotRegistry botRegistry(@Value("${mcagents.bots.max:16}") int max) {
        return new BotRegistry(max);
    }

    /*
    Not a bean: Spring Boot already publishes an ObjectMapper for HTTP, and a second one of the
    same type turns every injection point into a naming question.
    */
    private static ObjectMapper wireMapper() {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * Deadlines, heartbeats and feed waits. Small on purpose: everything scheduled here either
     * completes at once or hands off, so a pool that grew with the number of bots would only be
     * hiding work that blocks where it should not.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService timers() {
        return Executors.newScheduledThreadPool(2, Thread.ofPlatform().name("mcagents-timer-", 0).factory());
    }

    /**
     * Nothing but the MCP endpoint is behind the token. A readiness probe cannot carry one, and
     * there is nothing behind a probe worth reaching.
     */
    @Bean
    public FilterRegistrationBean<Filter> mcpAuth(
            @Value("${mcagents.auth.token:}") String token) {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();

        if (token.isBlank()) {
            log.warn("MCP_AUTH_TOKEN is not set, so /mcp is open to anything that can reach it");
            /*
            Disabled, but still carrying a filter: Spring asks every registration to describe
            itself while the context starts, and a description is built from the filter. Leaving
            it null takes the whole application down before the warning above means anything.
            */
            registration.setFilter((request, response, chain) -> chain.doFilter(request, response));
            registration.setEnabled(false);
            return registration;
        }

        registration.setFilter(new BearerTokenFilter(token));
        registration.addUrlPatterns("/mcp", "/mcp/*");
        return registration;
    }

    /**
     * How join-server asks for a bot that is not running.
     *
     * <p>Built once at startup so the answer to "is there a cluster" is settled before any agent
     * asks, and a laptop pays nothing for a client it will never use. A cluster that refuses the
     * connection here is the same as no cluster: the tool says a bot has to be started by hand,
     * which is true either way.
     */
    @Bean(destroyMethod = "")
    public BotProvisioner botProvisioner(
            @Value("${mcagents.bots.provision:auto}") String provision,
            @Value("${mcagents.bots.namespace:}") String namespace,
            @Value("${mcagents.bots.mcp-host:}") String mcpHost,
            @Value("${mcagents.bots.profile.kind:}") String profileKind,
            @Value("${mcagents.bots.profile.name:}") String profileName,
            @Value("${mcagents.bot-link.port:8765}") int port) {
        if (!wanted(provision)) {
            log.info("not running in a cluster, so join-server uses bots that dial in");
            return new BotProvisioner(null, null, null, port, null);
        }

        try {
            KubernetesClient client = new KubernetesClientBuilder().build();
            String where = namespace.isBlank() ? client.getNamespace() : namespace;

            if (where == null) {
                log.info("no Kubernetes namespace is in scope, so join-server cannot start a bot");
                return new BotProvisioner(null, null, null, port, null);
            }

            client.getKubernetesVersion();
            log.info("bots can be started in namespace {}", where);

            BotProvisioner.Profile profile = profileName.isBlank() ? null
                    : new BotProvisioner.Profile(profileKind.isBlank() ? "MinecraftBotProfile" : profileKind, profileName);

            return new BotProvisioner(client, where, mcpHost.isBlank() ? defaultHost(where) : mcpHost, port, profile);
        } catch (RuntimeException e) {
            log.info("no cluster to start bots in ({}), so join-server uses bots that dial in", e.getMessage());
            return new BotProvisioner(null, null, null, port, null);
        }
    }

    /**
     * Whether to go looking for a cluster at all.
     *
     * <p>"auto" means only from inside one. A kubeconfig on a laptop points at whatever the
     * developer last used, and quietly creating bot pods there -- or blocking startup while
     * reaching for it -- is not something a default should do. "always" is for running this
     * server locally against a cluster on purpose.
     */
    private static boolean wanted(String provision) {
        return switch (provision) {
            case "always" -> true;
            case "never" -> false;
            default -> System.getenv("KUBERNETES_SERVICE_HOST") != null;
        };
    }

    /** The Service the chart creates. A bot needs a name it can resolve, not this pod's address. */
    private static String defaultHost(String namespace) {
        return "mc-agents-mcp-server.%s.svc".formatted(namespace);
    }

    @Bean
    public BotLinkServer botLinkServer(Catalog catalog, BotRegistry bots,
            ScheduledExecutorService timers, @Value("${mcagents.bot-link.port:8765}") int port,
            @Value("${mcagents.bot-link.repeat-flush-ms:1000}") int repeatFlushMs,
            @Value("${mcagents.bot-link.muted-feeds:}") Set<String> mutedFeeds) {
        return new BotLinkServer(catalog, bots, wireMapper(), timers, port, repeatFlushMs, mutedFeeds);
    }
}
