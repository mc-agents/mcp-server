package kr.junhyung.mcagents.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import kr.junhyung.mcagents.bot.BotLink;
import kr.junhyung.mcagents.bot.BotProvisioner;
import kr.junhyung.mcagents.bot.BotRegistry;
import kr.junhyung.mcagents.bot.BotSession;
import kr.junhyung.mcagents.catalog.Catalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * A bot this server asked the cluster for is given back whatever state it ended in. A leave that
 * only released a bot standing in a world left the pod of every failed join and restart running.
 */
class OrchestrationTest {

    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    private final List<AutoCloseable> open = new ArrayList<>();
    private final Catalog catalog = Catalog.load();
    private final BotRegistry bots = new BotRegistry(8);
    private final FakeCluster cluster = new FakeCluster();
    private final Orchestration orchestration = new Orchestration(bots, cluster,
            new RegionTools(bots, new RemoteTools(catalog), catalog), Duration.ofMillis(200));

    /** The cluster as the provisioner sees it: which bots it was asked for, and what came of them. */
    private static final class FakeCluster extends BotProvisioner {

        private final List<String> declared = new ArrayList<>();
        private final List<String> released = new ArrayList<>();
        private String failure;
        private Duration age = Duration.ofMinutes(10);

        FakeCluster() {
            super(null, null, null, 0, null, null);
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public boolean request(String name, String kind, String minecraftVersion, String owner) {
            if (declared.contains(name)) {
                return false;
            }
            declared.add(name);
            return true;
        }

        @Override
        public String failure(String name) {
            return failure;
        }

        @Override
        public Duration age(String name) {
            return declared.contains(name) ? age : null;
        }

        @Override
        public boolean release(String name) {
            if (!declared.remove(name)) {
                return false;
            }
            released.add(name);
            return true;
        }
    }

    @AfterEach
    void stop() throws Exception {
        for (AutoCloseable closeable : open) {
            closeable.close();
        }
        timers.shutdownNow();
    }

    /* A session always has a link, so it gets a real loopback socket pair. */
    private void linked(String name) throws IOException {
        ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Socket botSide = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
        Socket serverSide = listener.accept();
        open.add(listener);
        open.add(botSide);
        bots.add(new BotSession(name, "azalea", new BotLink(serverSide, JsonMapper.builder().build(), timers), timers));
    }

    private String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    @Test
    void aBotThatIsNotInAWorldIsStillGivenBack() throws IOException {
        cluster.declared.add("Qa_Bot1");
        linked("Qa_Bot1");

        String left = text(orchestration.call(catalog.get("leave-server"), Map.of("bot", "Qa_Bot1")));

        assertEquals(List.of("Qa_Bot1"), cluster.released);
        assertTrue(left.contains("is not in a world, and the bot it was running on was given back"), left);
    }

    @Test
    void aBotThatNeverLinkedIsGivenBackByName() {
        cluster.declared.add("qa-stuck-1");

        String left = text(orchestration.call(catalog.get("leave-server"), Map.of("bot", "qa-stuck-1")));

        assertEquals(List.of("qa-stuck-1"), cluster.released);
        assertTrue(left.contains("never linked"), left);
    }

    @Test
    void aJoinWhoseBotNeverLinksGivesBackWhatItAskedFor() {
        cluster.failure = "image not found";

        McpSchema.CallToolResult joined = orchestration.call(catalog.get("join-server"),
                Map.of("name", "qa-fail-1", "host", "paper.mc-agents.svc", "kind", "azalea"));

        assertTrue(joined.isError(), text(joined));
        assertTrue(text(joined).contains("image not found"), text(joined));
        assertEquals(List.of("qa-fail-1"), cluster.released);
    }

    /** A booting bot outlasts one call's patience; the call says so and leaves the bot to link. */
    @Test
    void aJoinThatRunsOutOfPatienceLeavesTheBootingBotForTheNextCall() {
        cluster.age = Duration.ofSeconds(30);

        McpSchema.CallToolResult joined = orchestration.call(catalog.get("join-server"),
                Map.of("name", "qa-slow-1", "host", "paper.mc-agents.svc", "kind", "fabric"));

        assertTrue(joined.isError(), text(joined));
        assertTrue(text(joined).contains("is still starting") && text(joined).contains("Call join-server again"), text(joined));
        assertTrue(cluster.released.isEmpty(), cluster.released::toString);
    }

    @Test
    void aBotThatNeverLinksWithinTheWholeStartIsGivenBack() {
        McpSchema.CallToolResult joined = orchestration.call(catalog.get("join-server"),
                Map.of("name", "qa-dead-1", "host", "paper.mc-agents.svc", "kind", "fabric"));

        assertTrue(text(joined).contains("never dialled in"), text(joined));
        assertEquals(List.of("qa-dead-1"), cluster.released);
    }

    @Test
    void aHostWrittenWithItsPortIsTheSameAsTheTwoGivenApart() {
        assertEquals(List.of("game-velocity.hyperfarm-local.svc", "25565"),
            List.of(Orchestration.hostAndPort("game-velocity.hyperfarm-local.svc:25565", null)));
        assertEquals(List.of("paper.mc-agents.svc", "25566"),
            List.of(Orchestration.hostAndPort("paper.mc-agents.svc", 25566)));
        assertEquals(List.of("10.0.0.5", "25570"), List.of(Orchestration.hostAndPort("10.0.0.5:25570", 25570)));
        assertThrows(IllegalArgumentException.class, () -> Orchestration.hostAndPort("paper.mc-agents.svc:25565", 25566));
    }

    @Test
    void aJoinTakesTheBotByTheNameEveryOtherToolUsesForIt() {
        IllegalArgumentException conflicting = assertThrows(IllegalArgumentException.class, () -> orchestration.call(
                catalog.get("join-server"), Map.of("bot", "qa-a", "name", "qa-b", "host", "paper.mc-agents.svc")));
        assertTrue(conflicting.getMessage().contains("give one of them"), conflicting.getMessage());

        cluster.failure = "image not found";
        McpSchema.CallToolResult byBot = orchestration.call(catalog.get("join-server"),
                Map.of("bot", "qa-bot-arg", "host", "paper.mc-agents.svc", "kind", "azalea"));

        assertTrue(text(byBot).contains("qa-bot-arg"), text(byBot));
    }

    /** One asked for by an earlier join is that join's to give back, not this one's. */
    @Test
    void aJoinThatFoundTheBotAlreadyAskedForLeavesItAlone() {
        cluster.declared.add("qa-shared-1");
        cluster.failure = "image not found";

        orchestration.call(catalog.get("join-server"),
                Map.of("name", "qa-shared-1", "host", "paper.mc-agents.svc", "kind", "azalea"));

        assertTrue(cluster.released.isEmpty(), cluster.released::toString);
    }
}
