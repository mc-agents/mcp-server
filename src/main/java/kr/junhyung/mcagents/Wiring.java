package kr.junhyung.mcagents;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import kr.junhyung.mcagents.bot.BotLinkServer;
import kr.junhyung.mcagents.bot.BotProvisioner;
import jakarta.servlet.Filter;
import kr.junhyung.mcagents.http.BearerTokenFilter;
import kr.junhyung.mcagents.bot.BotRegistry;
import kr.junhyung.mcagents.catalog.Catalog;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
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
    public BotRegistry botRegistry(@Value("${mcagents.bots.max:16}") int max, MeterRegistry meters) {
        BotRegistry bots = new BotRegistry(max);
        Gauge.builder("mcagents.bots.linked", bots, BotRegistry::size)
                .description("Bots linked to this server right now")
                .register(meters);
        return bots;
    }

    /**
     * Where the MCP port listens follows from the token. A server with no token is a laptop's,
     * and a laptop's must not answer the coffee shop; one with a token is a pod's, and a pod is
     * reached through its Service. {@code MCP_BIND_HOST} overrides either way. A customizer
     * rather than a yaml default so a chart or operator from before this rule, which sets no
     * bind host, keeps working.
     */
    @Bean
    public WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> mcpBindAddress(
            @Value("${mcagents.mcp.bind-host:}") String bindHost,
            @Value("${mcagents.auth.token:}") String token) {
        String host = !bindHost.isBlank() ? bindHost : token.isBlank() ? "127.0.0.1" : "0.0.0.0";

        return factory -> {
            try {
                factory.setAddress(InetAddress.getByName(host));
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("MCP_BIND_HOST \"%s\" is not an address this host can bind".formatted(host), e);
            }
            log.info("/mcp listens on {}", host);
        };
    }

    /**
     * The transport the autoconfiguration would build, plus what it leaves out.
     *
     * <p>The MCP specification has servers validate {@code Origin} so a page in a browser cannot
     * drive a server on localhost through the visitor's own machine. The SDK's validator checks
     * exactly that: a request with no {@code Origin} passes, which is what Claude Code and every
     * client outside a browser sends; one that carries it must match an allowed origin, with a
     * {@code :*} suffix standing for any port. The {@code Host} header is left unchecked, as the
     * validator does when given no allowed hosts, since the same server answers as a Service
     * name in a cluster and as localhost on a laptop.
     *
     * <p>Sessions are evicted when idle and capped, because a client that initialised and went
     * away otherwise holds its slot for the life of the process. The idle timeout has to be longer
     * than the keep-alive interval, which is what lets a live client be told apart from a gone
     * one, and {@code build()} refuses the pair when it is not.
     */
    @Bean
    public WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper,
            McpServerStreamableHttpProperties transport,
            @Value("${mcagents.mcp.allowed-origins:}") List<String> allowedOrigins,
            @Value("${mcagents.mcp.session-idle-timeout:30m}") Duration sessionIdleTimeout,
            @Value("${mcagents.mcp.max-sessions:256}") long maxSessions) {
        List<String> origins = new ArrayList<>(List.of("http://localhost:*", "http://127.0.0.1:*"));
        allowedOrigins.stream().map(String::trim).filter(origin -> !origin.isEmpty()).forEach(origins::add);

        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(transport.getMcpEndpoint())
                .keepAliveInterval(transport.getKeepAliveInterval())
                .disallowDelete(transport.isDisallowDelete())
                .securityValidator(DefaultServerTransportSecurityValidator.builder().allowedOrigins(origins).build())
                .sessionIdleTimeout(sessionIdleTimeout)
                .maxSessions(maxSessions)
                .build();
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
     * The MCP endpoint and the region files are behind the token; nothing else is. A readiness
     * probe cannot carry one, and there is nothing behind a probe worth reaching.
     */
    @Bean
    public FilterRegistrationBean<Filter> mcpAuth(
            @Value("${mcagents.auth.token:}") String token) {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();

        if (token.isBlank()) {
            log.warn("MCP_AUTH_TOKEN is not set, so /mcp and /regions are open to anything that can reach them");
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
        registration.addUrlPatterns("/mcp", "/mcp/*", "/regions", "/regions/*");
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
            @Value("${mcagents.bots.link-secret:}") String linkSecret,
            @Value("${mcagents.bot-link.port:8765}") int port) {
        if (!wanted(provision)) {
            log.info("not running in a cluster, so join-server uses bots that dial in");
            return new BotProvisioner(null, null, null, port, null, null);
        }

        try {
            KubernetesClient client = new KubernetesClientBuilder().build();
            String where = namespace.isBlank() ? client.getNamespace() : namespace;

            if (where == null) {
                log.info("no Kubernetes namespace is in scope, so join-server cannot start a bot");
                return new BotProvisioner(null, null, null, port, null, null);
            }

            client.getKubernetesVersion();
            log.info("bots can be started in namespace {}", where);

            BotProvisioner.Profile profile = profileName.isBlank() ? null
                    : new BotProvisioner.Profile(profileKind.isBlank() ? "MinecraftBotProfile" : profileKind, profileName);

            return new BotProvisioner(client, where, mcpHost.isBlank() ? defaultHost(where) : mcpHost, port, profile,
                    linkSecret.isBlank() ? null : linkSecret);
        } catch (RuntimeException e) {
            log.info("no cluster to start bots in ({}), so join-server uses bots that dial in", e.getMessage());
            return new BotProvisioner(null, null, null, port, null, null);
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
            case "auto" -> System.getenv("KUBERNETES_SERVICE_HOST") != null;
            /* A typo used to mean auto, silently. */
            default -> throw new IllegalArgumentException(
                    "MCP_BOTS_PROVISION is \"%s\"; it has to be auto, always or never".formatted(provision));
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
            @Value("${mcagents.bot-link.muted-feeds:}") Set<String> mutedFeeds,
            @Value("${mcagents.bot-link.token:}") String linkToken,
            MeterRegistry meters) {
        return new BotLinkServer(catalog, bots, wireMapper(), timers, port, repeatFlushMs, mutedFeeds, linkToken, meters);
    }
}
