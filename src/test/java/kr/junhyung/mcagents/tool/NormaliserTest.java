package kr.junhyung.mcagents.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import kr.junhyung.mcagents.catalog.Catalog;
import kr.junhyung.mcagents.catalog.ToolSpec;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What reaches a bot, against the real catalogue.
 *
 * <p>This is the invariant the whole two-kinds-of-bot design rests on: neither bot decides what an
 * omitted argument meant, because by the time the call leaves the server nothing is omitted.
 */
class NormaliserTest {

    private final Catalog catalog = Catalog.load();

    private Map<String, Object> wire(String tool, Map<String, Object> arguments) {
        return Normaliser.normalise(catalog.require(tool), arguments);
    }

    @Test
    void anOmittedArgumentArrivesAsTheCatalogueDefault() {
        Map<String, Object> sent = wire("find-blocks", Map.of("blockType", "minecraft:diamond_ore"));

        assertEquals("minecraft:diamond_ore", sent.get("blockType"));
        assertEquals(16.0, sent.get("maxDistance"));
        assertEquals(1, sent.get("count"));
    }

    @Test
    void whatTheCallerGaveWinsOverTheDefault() {
        Map<String, Object> sent = wire("find-blocks",
                Map.of("blockType", "minecraft:stone", "maxDistance", 8, "count", 12));

        assertEquals(8.0, sent.get("maxDistance"));
        assertEquals(12, sent.get("count"));
    }

    /*
    A bot that clamped for itself would report having searched 64 blocks when the other kind
    searched 256, and the difference would only show up as two agents disagreeing about a world.
    */
    @Test
    void aValueOutOfRangeIsBroughtBackInBeforeItIsSent() {
        Map<String, Object> sent = wire("find-blocks",
                Map.of("blockType", "minecraft:stone", "count", 100_000));

        assertEquals(256, sent.get("count"));
    }

    @Test
    void anIntegerStaysAnIntegerAndAFractionStaysAFraction() {
        Map<String, Object> sent = wire("find-blocks",
                Map.of("blockType", "minecraft:stone", "maxDistance", 12, "count", 3.0));

        assertInstanceOf(Double.class, sent.get("maxDistance"));
        assertInstanceOf(Integer.class, sent.get("count"));
    }

    /*
    "Any type" is a decision the server made, so it travels as one. Leaving the key out would hand
    each kind of bot the same question and no answer.
    */
    @Test
    void anArgumentWhoseAbsenceMeansSomethingTravelsAsAnExplicitNull() {
        Map<String, Object> sent = wire("find-entity", Map.of());

        assertTrue(sent.containsKey("type"));
        assertNull(sent.get("type"));
    }

    @Test
    void theBotArgumentNeverReachesABot() {
        Map<String, Object> sent = wire("find-entity", Map.of("bot", "alice"));

        assertTrue(sent.containsKey("maxDistance"));
        assertTrue(!sent.containsKey("bot"), "a bot has one game connection and no idea of names");
    }

    @Test
    void aRequiredArgumentThatWasNotGivenIsTheCallersProblemNotTheCataloguesGap() {
        Map<String, Object> nothing = new HashMap<>();

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> wire("find-blocks", nothing));

        assertTrue(thrown.getMessage().contains("blockType"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("not given"), thrown.getMessage());
    }

    /*
    A step inside run-inputs is an object of its own, and the invariant holds for it the same way:
    the bot reads a step with thirteen keys, never one with the one key the caller typed.
    */
    @Test
    void aStepInsideAnArrayArrivesWithEveryDefaultFilled() {
        Map<String, Object> sent = wire("run-inputs", Map.of("steps", List.of(Map.of("click", 13))));

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("press", null);
        expected.put("slot", null);
        expected.put("holdTicks", 1);
        expected.put("useItem", null);
        expected.put("click", 13);
        expected.put("button", "left");
        expected.put("shift", false);
        expected.put("mode", "click");
        expected.put("hotbar", null);
        expected.put("command", null);
        expected.put("wait", null);
        expected.put("waitFor", null);
        expected.put("feed", "actionBar");

        assertEquals(List.of(expected), sent.get("steps"));
        assertEquals(10_000, sent.get("timeoutMs"));
    }

    @Test
    void aValueOutOfRangeInsideAStepIsBroughtBackInToo() {
        Map<String, Object> sent = wire("run-inputs",
                Map.of("steps", List.of(Map.of("press", "use", "holdTicks", 5_000))));

        assertEquals(1_200, stepOf(sent, 0).get("holdTicks"));
    }

    @Test
    void aStepThatIsNotAnObjectIsRefusedByItsIndex() {
        Map<String, Object> arguments = Map.of("steps", List.of(Map.of("press", "jump"), "wait"));

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> wire("run-inputs", arguments));

        assertEquals("\"run-inputs\" steps[1] is not an object", thrown.getMessage());
    }

    /**
     * The same bounds, on the tools whose arguments never cross the wire. A local tool read them
     * raw, so a timeout of two billion pinned a thread and a port of 99999 was handed to a bot.
     * Refused rather than clamped: the endpoint's own validation refuses the same value in the
     * same terms, and a clamped port is a different server.
     */
    @Test
    void anArgumentOutsideItsRangeIsRefusedOnAToolTheServerAnswersAlone() {
        ToolSpec ping = catalog.require("ping-server");
        ToolSpec join = catalog.require("join-server");
        ToolSpec wait = catalog.require("wait-for-chat");

        assertEquals(30_000, Normaliser.bound(ping, Map.of("host", "paper", "timeoutMs", 30_000)).get("timeoutMs"));

        IllegalArgumentException timeout = assertThrows(IllegalArgumentException.class,
                () -> Normaliser.bound(ping, Map.of("host", "paper", "timeoutMs", Integer.MAX_VALUE)));
        IllegalArgumentException port = assertThrows(IllegalArgumentException.class,
                () -> Normaliser.bound(join, Map.of("host", "paper", "port", 99_999)));
        IllegalArgumentException pattern = assertThrows(IllegalArgumentException.class,
                () -> Normaliser.bound(wait, Map.of("pattern", "x".repeat(257))));

        assertTrue(timeout.getMessage().contains("timeoutMs") && timeout.getMessage().contains("30000"), timeout.getMessage());
        assertTrue(port.getMessage().contains("port") && port.getMessage().contains("65535"), port.getMessage());
        assertTrue(pattern.getMessage().contains("pattern") && pattern.getMessage().contains("256"), pattern.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stepOf(Map<String, Object> wire, int index) {
        return ((List<Map<String, Object>>) wire.get("steps")).get(index);
    }

    /**
     * The deadline sits above the tool's own wait, so the tool answers before the deadline does.
     * Equal timers race, and the deadline's message is the less useful of the two: "did not finish
     * within 10000ms" where the tool had "no window opened" ready to send.
     */
    @Test
    void theDeadlineLeavesTheToolRoomToAnswerFirst() {
        ToolSpec walk = catalog.require("move-to-position");
        Map<String, Object> far = wire("move-to-position", Map.of("x", 0, "y", 64, "z", 0, "timeoutMs", 5_000));
        Map<String, Object> plain = wire("move-to-position", Map.of("x", 0, "y", 64, "z", 0));

        assertTrue(Normaliser.deadlineOf(walk, far) > 5_000);
        assertEquals(61_000, Normaliser.deadlineOf(walk, plain));
    }
}
