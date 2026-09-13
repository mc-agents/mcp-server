package kr.junhyung.mcagents.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every tool the server offers, loaded once at startup.
 *
 * <p>The catalogue is the server's, not a bot's. {@code tools/list} has to be complete before any
 * bot has linked — an MCP client reads it when the session opens, and one that connects first must
 * still see everything — so it cannot be assembled from what happens to be connected.
 */
public final class Catalog {

    private static final TypeReference<Map<String, Object>> SCHEMA = new TypeReference<>() {};

    private static final String RESOURCE = "/catalog.json";

    private final String version;
    private final int protocol;
    private final Map<String, ToolSpec> tools;

    private Catalog(String version, int protocol, Map<String, ToolSpec> tools) {
        this.version = version;
        this.protocol = protocol;
        this.tools = tools;
    }

    public static Catalog load() {
        try (InputStream in = Catalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the classpath");
            }
            return parse(JsonMapper.builder().build(), in);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + RESOURCE, e);
        }
    }

    static Catalog parse(ObjectMapper mapper, InputStream in) throws IOException {
        JsonNode root = mapper.readTree(in);
        Map<String, ToolSpec> parsed = new LinkedHashMap<>();

        for (JsonNode node : root.get("tools")) {
            String name = node.get("name").asString();
            ToolSpec spec = new ToolSpec(
                    name,
                    node.get("group").asString(),
                    node.get("description").asString(),
                    ToolSpec.Route.of(node.get("route").asString()),
                    strings(node.get("kinds")),
                    node.get("exclusive").asBoolean(),
                    node.get("untrusted").asBoolean(),
                    node.get("structured").asBoolean(),
                    /*
                    A tool the catalogue does not mark needs a world. screenshot is the exception,
                    and the exception is the point: the screen a bot is stuck on is the answer to
                    why it cannot get into one.
                    */
                    !node.has("needsWorld") || node.get("needsWorld").asBoolean(),
                    node.get("defaultDeadlineMs").asInt(),
                    mapper.convertValue(node.get("inputSchema"), SCHEMA),
                    node.has("wireSchema") ? mapper.convertValue(node.get("wireSchema"), SCHEMA) : null,
                    node.has("wireSchemaHash") ? node.get("wireSchemaHash").asString() : null);

            if (parsed.put(name, spec) != null) {
                throw new IllegalStateException("the catalogue declares \"%s\" twice".formatted(name));
            }
        }

        return new Catalog(root.get("catalogVersion").asString(), root.get("protocol").asInt(), parsed);
    }

    private static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }

    public String version() {
        return version;
    }

    public int protocol() {
        return protocol;
    }

    /** Every kind of bot the catalogue knows of, which is what "every kind" means in a description. */
    public Set<String> kinds() {
        return tools.values().stream()
            .flatMap(spec -> spec.kinds().stream())
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    public List<ToolSpec> all() {
        return List.copyOf(tools.values());
    }

    public ToolSpec get(String name) {
        return tools.get(name);
    }

    public ToolSpec require(String name) {
        ToolSpec found = tools.get(name);
        if (found == null) {
            throw new IllegalArgumentException("there is no tool called \"%s\"".formatted(name));
        }
        return found;
    }

    public int size() {
        return tools.size();
    }
}
