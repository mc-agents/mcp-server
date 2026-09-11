package kr.junhyung.mcagents;

import kr.junhyung.mcagents.bot.BotLinkServer;
import kr.junhyung.mcagents.bot.BotRegistry;
import kr.junhyung.mcagents.catalog.Catalog;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Value;
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

    @Bean
    public BotLinkServer botLinkServer(Catalog catalog, BotRegistry bots,
            ScheduledExecutorService timers, @Value("${mcagents.bot-link.port:8765}") int port) {
        return new BotLinkServer(catalog, bots, wireMapper(), timers, port);
    }
}
