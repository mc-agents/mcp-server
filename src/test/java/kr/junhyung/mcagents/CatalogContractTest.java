package kr.junhyung.mcagents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import kr.junhyung.mcagents.render.Renderers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The catalogue is the contract all four repositories compile against, and the only copy of it is
 * this file. What this holds it to is that the hash still describes the contents, and that a tool
 * which promises structured output has both a schema saying what it sends and a renderer able to
 * turn that into the sentence an agent reads.
 */
class CatalogContractTest {

    private static final Path CATALOG = Path.of("catalog/catalog.json");
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** Local tools answer from the server's own state, so no bot ever sends their DTO. */
    private static final String SERVER_LOCAL_ROUTE = "local";

    private static JsonNode catalog() throws IOException {
        return MAPPER.readTree(Files.readString(CATALOG));
    }

    private static List<JsonNode> tools() throws IOException {
        List<JsonNode> tools = new ArrayList<>();
        catalog().get("tools").forEach(tools::add);

        assertFalse(tools.isEmpty(), "the catalogue lists no tools");

        return tools;
    }

    /**
     * Editing the catalogue without rehashing it leaves every bot agreeing with a version that no
     * longer exists, and nothing at runtime would say so.
     */
    @Test
    void theHashDescribesTheToolsThatAreInThere() throws IOException, NoSuchAlgorithmException {
        JsonNode catalog = catalog();

        assertEquals(digestOf(catalog.get("tools")), catalog.get("catalogHash").asString());
    }

    /**
     * A bot sends this hash at handshake and the server disables any tool where it disagrees. If
     * the hash stopped describing the schema, every bot would agree with a version of the arguments
     * that no longer exists, and the check would pass while being wrong.
     *
     * <p>Recomputing here rather than comparing against a stored list is deliberate: it is the same
     * function the protocol document specifies, so a bot in another language that follows the
     * document arrives at the same string.
     */
    @Test
    void everyWireSchemaHashDescribesTheSchemaBesideIt() throws IOException, NoSuchAlgorithmException {
        for (JsonNode tool : tools()) {
            JsonNode wire = tool.path("wireSchema");

            if (wire.isMissingNode() || wire.isNull()) {
                continue;
            }
            assertEquals(digestOf(wire), tool.path("wireSchemaHash").asString(),
                    tool.get("name").asString() + " carries a hash of a schema it no longer has");
        }
    }

    /** Keys sorted recursively, no whitespace, as docs/bot-protocol.md specifies. */
    private static String digestOf(JsonNode node) throws NoSuchAlgorithmException {
        byte[] canonical = MAPPER.writeValueAsString(sortKeys(MAPPER.treeToValue(node, Object.class)))
                .getBytes(StandardCharsets.UTF_8);

        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    }

    @Test
    void everyStructuredToolSaysWhatItSendsAndHasARenderer() throws IOException {
        for (JsonNode tool : tools()) {
            String name = tool.get("name").asString();

            if (!tool.get("structured").asBoolean()) {
                continue;
            }

            assertTrue(tool.has("resultSchema"), name + " is structured but says nothing about what it sends");
            assertTrue(Renderers.handles(name), name + " is structured but nothing renders it");
        }
    }

    /**
     * {@code structured} means the bot sends a DTO and the server renders it. A tool the server
     * answers alone sends nothing over the wire, so marking one structured describes an exchange
     * that does not happen, and it is how wait-for-server ended up claiming a schema it had never
     * been given.
     */
    @Test
    void noToolTheServerAnswersAloneClaimsToSendADto() throws IOException {
        for (JsonNode tool : tools()) {
            if (SERVER_LOCAL_ROUTE.equals(tool.get("route").asString())) {
                assertFalse(tool.get("structured").asBoolean(),
                        tool.get("name").asString() + " is answered by the server, so nothing sends it a DTO");
            }
        }
    }

    @Test
    void nothingRendersATextOnlyTool() throws IOException {
        for (JsonNode tool : tools()) {
            String name = tool.get("name").asString();

            if (tool.get("structured").asBoolean()) {
                continue;
            }

            assertFalse(tool.has("resultSchema"), name + " is not structured but carries a result schema");
            assertFalse(Renderers.handles(name), name + " is not structured but has a renderer");
        }
    }

    /**
     * The server fills every default before a call leaves it, which is only possible if the
     * catalogue states them in a form a machine can read.
     *
     * <p>Forty-one wire properties said their default in prose -- "(default: 16)" -- and nowhere
     * else. Every tool with an optional argument failed the moment a caller left it out, and the
     * message blamed the caller. A prose default is for the reader; this is for the code.
     */
    @Test
    void everyOptionalWireArgumentStatesItsDefaultWhereTheCodeCanReadIt() throws IOException {
        for (JsonNode tool : tools()) {
            String name = tool.get("name").asString();
            JsonNode wire = tool.path("wireSchema");

            if (wire.isMissingNode() || wire.isNull()) {
                continue;
            }

            List<String> required = new ArrayList<>();
            tool.path("inputSchema").path("required").forEach(field -> required.add(field.asString()));

            wire.path("properties").propertyStream().forEach(property -> {
                if (required.contains(property.getKey())) {
                    return;
                }
                assertTrue(property.getValue().has("default"),
                        "%s takes \"%s\" optionally but never says what it defaults to"
                                .formatted(name, property.getKey()));
            });
        }
    }

    /** A renderer for a tool the catalogue has never heard of would never be reached. */
    @Test
    void everyRendererNamesAToolTheCatalogueHas() throws IOException {
        List<String> names = new ArrayList<>();

        for (JsonNode tool : tools()) {
            names.add(tool.get("name").asString());
        }

        for (String tool : Renderers.tools()) {
            assertTrue(names.contains(tool), tool + " is rendered but is not in the catalogue");
        }
    }

    private static Object sortKeys(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();

            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put((String) entry.getKey(), sortKeys(entry.getValue()));
            }

            return sorted;
        }

        if (value instanceof List<?> list) {
            return list.stream().map(CatalogContractTest::sortKeys).toList();
        }

        return value;
    }
}
