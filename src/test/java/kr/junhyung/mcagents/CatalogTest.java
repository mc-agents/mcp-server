package kr.junhyung.mcagents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import kr.junhyung.mcagents.catalog.Catalog;
import kr.junhyung.mcagents.catalog.ToolSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Loaded from the shipped catalogue, so these assert the contract rather than a fixture. */
class CatalogTest {

    private final Catalog catalog = Catalog.load();

    @Test
    void theShippedCatalogueLoads() {
        assertEquals(1, catalog.protocol());
        assertTrue(catalog.size() >= 60, "the catalogue should carry every tool, got " + catalog.size());
    }

    @Test
    void everyToolHasARouteAndAtLeastOneKindThatCanRunIt() {
        for (ToolSpec tool : catalog.all()) {
            assertNotNull(tool.route(), tool.name());
            assertFalse(tool.kinds().isEmpty(), tool.name());
        }
    }

    /*
    A bot only ever receives a normalised schema, so anything routed to one must carry it along
    with the hash a handshake is checked against. A local tool has neither by design.
    */
    @Test
    void aToolRoutedToABotCarriesWhatABotNeedsToVerifyIt() {
        for (ToolSpec tool : catalog.all()) {
            boolean reachesABot = tool.route() == ToolSpec.Route.RPC || tool.route() == ToolSpec.Route.COMPOSE;

            if (reachesABot) {
                assertNotNull(tool.wireSchema(), tool.name() + " goes to a bot without a wire schema");
                assertNotNull(tool.wireSchemaHash(), tool.name() + " goes to a bot without a hash");
            }
        }
    }

    /* The bot argument is the server's business. A bot has one game connection and no idea of names. */
    @Test
    void theWireSchemaNeverCarriesTheBotArgument() {
        for (ToolSpec tool : catalog.all()) {
            if (tool.wireSchema() == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            var properties = (java.util.Map<String, Object>) tool.wireSchema().get("properties");
            assertFalse(properties.containsKey("bot"), tool.name() + " would send bot to a bot");
        }
    }

    /* Nothing optional reaches a bot: the server has already filled defaults and clamped values. */
    @Test
    void everyWirePropertyIsRequiredBecauseTheServerHasAlreadyDecided() {
        for (ToolSpec tool : catalog.all()) {
            if (tool.wireSchema() == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            var properties = (java.util.Map<String, Object>) tool.wireSchema().get("properties");
            @SuppressWarnings("unchecked")
            var required = (List<String>) tool.wireSchema().get("required");

            assertEquals(properties.keySet().size(), required.size(), tool.name());
            assertTrue(required.containsAll(properties.keySet()), tool.name());
        }
    }

    /*
    The kinds list and the sentence that names them have to agree, so the sentence is generated.
    */
    @Test
    void aToolOnlyOneKindSupportsSaysSoInItsDescription() {
        ToolSpec screenshot = catalog.require("screenshot");

        assertEquals(List.of("fabric"), screenshot.kinds());
        assertTrue(screenshot.advertisedDescription().endsWith("Supported by bots of kind: fabric."),
                screenshot.advertisedDescription());
        assertFalse(screenshot.supportedBy("mineflayer"));
    }

    @Test
    void aToolEveryBotSupportsSaysNothingExtra() {
        ToolSpec position = catalog.require("get-position");

        assertEquals(position.description(), position.advertisedDescription());
        assertTrue(position.supportedBy("mineflayer"));
        assertTrue(position.supportedBy("fabric"));
    }

    /* find-entity went missing from generated docs once. Naming it here keeps it from happening quietly. */
    @Test
    void theToolsThatWentMissingBeforeAreDeclared() {
        for (String name : List.of("find-entity", "read-displays", "read-chat", "screenshot",
                "press-dialog-button", "wait-for-server", "restart-bot")) {
            assertNotNull(catalog.get(name), name + " is not in the catalogue");
        }
    }

    @Test
    void anUnknownToolIsRefusedByNameRatherThanReturningNull() {
        assertNull(catalog.get("no-such-tool"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> catalog.require("no-such-tool"))
                .getMessage().contains("no-such-tool"));
    }
}
