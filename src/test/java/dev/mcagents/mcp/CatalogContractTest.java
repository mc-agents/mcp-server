package dev.mcagents.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcagents.mcp.render.Renderers;
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
        Object tools = sortKeys(MAPPER.treeToValue(catalog.get("tools"), Object.class));
        byte[] canonical = MAPPER.writeValueAsString(tools).getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));

        assertEquals("sha256:" + digest, catalog.get("catalogHash").asString());
    }

    @Test
    void everyStructuredBotToolSaysWhatItSendsAndHasARenderer() throws IOException {
        for (JsonNode tool : tools()) {
            String name = tool.get("name").asString();

            if (!tool.get("structured").asBoolean() || SERVER_LOCAL_ROUTE.equals(tool.get("route").asString())) {
                continue;
            }

            assertTrue(tool.has("resultSchema"), name + " is structured but says nothing about what it sends");
            assertTrue(Renderers.handles(name), name + " is structured but nothing renders it");
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
