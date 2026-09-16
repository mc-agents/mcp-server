package kr.junhyung.mcagents.fixture;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * What a server does to a player between the login and the world: hold them in configuration, or
 * send them away from there.
 *
 * <p>A join has two halves. The server can refuse the login -- a whitelist, a ban -- and it can
 * accept it and then never let the player spawn: a plugin waiting on a resource pack, a transfer
 * that never comes, a kick from configuration. A bot that reported the second as the first sent an
 * agent to check the whitelist. The configure event runs off the main thread and may block, which
 * is the documented way to hold a connection there, so a name put on hold is held until it is
 * released or a minute has passed, whichever is first: a case that forgets to release must not
 * wedge the server for the cases after it.
 */
final class Gate implements Listener {

    private static final Duration LONGEST_HOLD = Duration.ofSeconds(60);

    private final Map<String, CountDownLatch> held = new ConcurrentHashMap<>();
    private final Set<String> bounced = ConcurrentHashMap.newKeySet();

    void hold(String name) {
        held.put(name, new CountDownLatch(1));
    }

    void release(String name) {
        CountDownLatch latch = held.remove(name);
        if (latch != null) {
            latch.countDown();
        }
    }

    /** The next login under this name is accepted and then disconnected from configuration, once. */
    void bounce(String name) {
        bounced.add(name);
    }

    @EventHandler
    void configure(AsyncPlayerConnectionConfigureEvent event) throws InterruptedException {
        PlayerConfigurationConnection connection = event.getConnection();
        String name = connection.getProfile().getName();
        if (name == null) {
            return;
        }
        if (bounced.remove(name)) {
            connection.disconnect(Component.text("QA bounce from configuration"));
            return;
        }
        CountDownLatch latch = held.get(name);
        if (latch != null) {
            latch.await(LONGEST_HOLD.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
