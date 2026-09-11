package kr.junhyung.mcagents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import kr.junhyung.mcagents.render.Renderers;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The strings asserted here are the strings {@code bot-mineflayer} used to build for itself, taken
 * off the wire from a real bot on a real Paper server before the formatting moved to this side.
 * Agent prompts and the automation around them read this text, so a renderer that improves the
 * wording is a renderer that has broken something: moving to DTOs is meant to be invisible from
 * outside.
 *
 * <p>Cases noted as a live capture came off that wire. The rest are written by hand for a branch
 * the test world did not reach, and their text comes from the same format functions.
 */
class RendererGoldenTest {

    private record Golden(String tool, String note, JsonNode data, String text) {

        @Override
        public String toString() {
            return tool + " - " + note;
        }
    }

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void rendersWhatTheBotUsedTo(Golden golden) {
        assertEquals(golden.text(), Renderers.render(golden.tool(), golden.data()).orElseThrow());
    }

    /**
     * A renderer with no case behind it is a renderer nothing holds to the old wording.
     */
    @Test
    void everyRendererHasAGoldenCase() throws IOException {
        Set<String> covered = new HashSet<>();

        for (Golden golden : cases()) {
            covered.add(golden.tool());
        }

        for (String tool : Renderers.tools()) {
            assertTrue(covered.contains(tool), tool + " has no golden case");
        }
    }
}
