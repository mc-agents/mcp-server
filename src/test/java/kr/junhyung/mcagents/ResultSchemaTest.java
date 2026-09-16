package kr.junhyung.mcagents;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The catalogue's {@code resultSchema} is what a bot in another language is written against, and
 * the golden fixtures are DTOs that came off the wire from real bots. The two drifted apart for
 * nineteen tools without anything saying so: a schema required components that a bot built before
 * they existed never sends, and read as a contract it would have refused every one of those bots
 * while the renderers went on accepting them. Holding each fixture to its schema keeps the schema
 * describing what the renderer takes.
 *
 * <p>Nothing validates at runtime. A DTO the renderer cannot read fails there with a message that
 * names the field; a schema check in front of it would cost every call a validation for a
 * disagreement that is caught here once.
 */
class ResultSchemaTest {

    private static final Path CATALOG = Path.of("catalog/catalog.json");
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static Map<String, Schema> schemas;

    private record Golden(String tool, String note, JsonNode data, String text) {

        @Override
        public String toString() {
            return tool + " - " + note;
        }
    }

    private static List<Golden> cases() throws IOException {
        Resource[] found = new PathMatchingResourcePatternResolver().getResources("classpath*:render/*.json");
        List<Golden> cases = new ArrayList<>();

        for (Resource resource : found) {
            try (InputStream stream = resource.getInputStream()) {
                cases.add(MAPPER.readValue(stream, Golden.class));
            }
        }

        assertFalse(cases.isEmpty(), "no golden cases were found on the classpath");

        return cases;
    }

    @BeforeAll
    static void loadSchemas() throws IOException {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        schemas = new HashMap<>();

        for (JsonNode tool : MAPPER.readTree(Files.readString(CATALOG)).get("tools")) {
            if (tool.has("resultSchema")) {
                schemas.put(tool.get("name").asString(), registry.getSchema(tool.get("resultSchema")));
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void everyGoldenDtoIsWhatItsToolsResultSchemaDescribes(Golden golden) {
        Schema schema = schemas.get(golden.tool());

        assertNotNull(schema, golden.tool() + " has a golden fixture but no result schema");

        List<Error> errors = schema.validate(golden.data());

        assertTrue(errors.isEmpty(), () -> "%s sends a DTO its result schema refuses:%n%s".formatted(
                golden.tool(),
                errors.stream().map(Error::toString).collect(Collectors.joining(System.lineSeparator()))));
    }
}
