package dev.mcagents.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcagents.mcp.bot.BotLink;
import dev.mcagents.mcp.bot.BotRegistry;
import dev.mcagents.mcp.bot.BotSession;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BotRegistryTest {

    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void stop() throws Exception {
        for (AutoCloseable closeable : open) {
            closeable.close();
        }
        timers.shutdownNow();
    }

    /*
    A session always has a link -- one is only created when a bot has dialled in -- so the test
    gives it a real socket pair rather than a null the production path could never see.
    */
    private BotSession session(String name) {
        try {
            ServerSocket listener = new ServerSocket(0);
            Socket botSide = new Socket("127.0.0.1", listener.getLocalPort());
            Socket serverSide = listener.accept();
            open.add(listener);
            open.add(botSide);

            BotLink link = new BotLink(serverSide, JsonMapper.builder().build(), timers);
            return new BotSession(name, "mineflayer", link, timers);
        } catch (IOException e) {
            throw new IllegalStateException("could not set up a loopback link for the test", e);
        }
    }

    @Test
    void oneBotMayBeLeftUnnamedBecauseThatIsTheCommonCase() {
        BotRegistry registry = new BotRegistry(8);
        BotSession only = session("alpha");
        registry.add(only);

        assertSame(only, registry.resolve(null));
        assertSame(only, registry.resolve("alpha"));
    }

    @Test
    void twoBotsMakeTheNameRequired() {
        BotRegistry registry = new BotRegistry(8);
        registry.add(session("alpha"));
        registry.add(session("beta"));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> registry.resolve(null));

        assertTrue(thrown.getMessage().contains("\"bot\" is required"));
        assertTrue(thrown.getMessage().contains("alpha"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("beta"), thrown.getMessage());
    }

    /* Naming what is there turns "no such bot" from a dead end into the next thing to try. */
    @Test
    void anUnknownNameIsAnsweredWithTheNamesThatDoExist() {
        BotRegistry registry = new BotRegistry(8);
        registry.add(session("alpha"));

        assertTrue(assertThrows(IllegalArgumentException.class, () -> registry.resolve("ghost"))
                .getMessage().contains("Connected bots: alpha."));
    }

    @Test
    void noBotsAtAllPointsAtJoinServerRatherThanListingNothing() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new BotRegistry(8).resolve(null))
                .getMessage().contains("Call join-server first"));
    }

    @Test
    void aNameIsNotReusedWhileItIsInUse() {
        BotRegistry registry = new BotRegistry(8);
        registry.add(session("alpha"));

        assertTrue(assertThrows(IllegalStateException.class, () -> registry.add(session("alpha")))
                .getMessage().contains("already connected"));
    }

    @Test
    void theLimitIsRefusedWithSomethingToDoAboutIt() {
        BotRegistry registry = new BotRegistry(2);
        registry.add(session("a"));
        registry.add(session("b"));

        assertTrue(assertThrows(IllegalStateException.class, () -> registry.add(session("c")))
                .getMessage().contains("leave-server"));
    }

    @Test
    void namesThatWouldNotSurviveBeingAUsernameAreRefusedUpFront() {
        for (String bad : List.of("", "-leading", "has space", "한글", "x".repeat(33))) {
            assertThrows(IllegalArgumentException.class, () -> BotRegistry.requireValidName(bad), bad);
        }
        for (String good : List.of("a", "alpha", "bot-1", "bot_1", "x".repeat(32))) {
            BotRegistry.requireValidName(good);
        }
    }

    @Test
    void removingABotFreesItsName() {
        BotRegistry registry = new BotRegistry(8);
        registry.add(session("alpha"));

        assertNotNull(registry.remove("alpha", "test"));
        assertNull(registry.remove("alpha", "test"));

        registry.add(session("alpha"));
        assertEquals(1, registry.size());
    }

    @Test
    void idleBotsAreFoundSoAForgottenOneDoesNotSitInAWorldForever() throws Exception {
        BotRegistry registry = new BotRegistry(8);
        BotSession stale = session("stale");
        registry.add(stale);

        Thread.sleep(5);
        long cutoff = System.currentTimeMillis();
        Thread.sleep(5);

        BotSession fresh = session("fresh");
        registry.add(fresh);

        assertEquals(List.of("stale"), registry.idleSince(cutoff).stream().map(BotSession::name).toList());
    }
}
