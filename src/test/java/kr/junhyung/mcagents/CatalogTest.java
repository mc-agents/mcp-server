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
import java.util.Set;
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

            /*
            Except a wait, which sends a bot nothing of its own: it polls another tool, and that
            tool's schema is the one that crosses the wire. A wire schema here would be a shape
            nothing ever sends.
            */
            if (reachesABot && tool.watches() == null) {
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
    The kinds list and the sentence that names them have to agree, so the sentence is generated --
    and it is generated against the kinds the catalogue has rather than against a count. A tool every
    kind runs says nothing, and one only fabric runs says so, which is what tells an agent holding an
    azalea bot not to ask it for a screenshot.
    */
    @Test
    void aToolEveryKindRunsSaysNothingAndOneThatOnlySomeRunSaysWhich() {
        ToolSpec position = catalog.require("get-position");
        ToolSpec screenshot = catalog.require("screenshot");

        assertEquals(Set.of("fabric", "azalea"), catalog.kinds());
        assertEquals(position.description(), position.advertisedDescription(catalog.kinds()));
        assertTrue(screenshot.advertisedDescription(catalog.kinds()).endsWith("Supported by bots of kind: fabric."));
        assertFalse(screenshot.supportedBy("azalea"));
    }

    @Test
    void aToolOnlySomeKindsSupportSaysSo() {
        ToolSpec screenshot = catalog.require("screenshot");

        assertTrue(screenshot.advertisedDescription(List.of("fabric", "another"))
                .endsWith("Supported by bots of kind: fabric."));
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
