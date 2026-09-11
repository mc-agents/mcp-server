package kr.junhyung.mcagents.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import kr.junhyung.mcagents.catalog.Catalog;
import kr.junhyung.mcagents.catalog.ToolSpec;
import java.util.HashMap;
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

    /** A timeout the caller asked for is the deadline; everything else falls back to the catalogue. */
    @Test
    void theDeadlineFollowsTheCallersTimeoutWhenThereIsOne() {
        ToolSpec walk = catalog.require("move-to-position");
        Map<String, Object> far = wire("move-to-position", Map.of("x", 0, "y", 64, "z", 0, "timeoutMs", 5_000));
        Map<String, Object> plain = wire("move-to-position", Map.of("x", 0, "y", 64, "z", 0));

        assertEquals(5_000, Normaliser.deadlineOf(walk, far));
        assertEquals(60_000, Normaliser.deadlineOf(walk, plain));
    }
}
