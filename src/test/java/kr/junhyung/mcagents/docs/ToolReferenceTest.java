package kr.junhyung.mcagents.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * The page in the tree is the page the catalogue renders to. A catalogue edit that forgets the
 * page fails here rather than shipping a reference that says something else.
 */
class ToolReferenceTest {

    @Test
    void theToolReferenceIsTheCatalogueRendered() throws IOException {
        String rendered = ToolReference.render(new ObjectMapper().readTree(ToolReference.CATALOG.toFile()));

        assertEquals(rendered, Files.readString(ToolReference.TARGET),
            "docs/tools.md is not what the catalogue renders to; run ./gradlew renderToolReference");
    }
}
