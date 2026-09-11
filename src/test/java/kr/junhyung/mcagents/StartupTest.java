package kr.junhyung.mcagents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.server.McpServerFeatures;
import java.util.List;
import kr.junhyung.mcagents.catalog.Catalog;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * That the application starts, and that the token does what it says.
 *
 * <p>Worth a Spring context because the thing this catches is wiring, which no unit test sees. The
 * filter registration for "no token configured" took the whole server down at startup, and the
 * only place that showed was CI.
 */
class StartupTest {

    private static final String HANDSHAKE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
             "capabilities":{},"clientInfo":{"name":"test","version":"0"}}}""";

    /** Status and headers only: what is being tested is who gets in, not what comes back. */
    private record Answer(HttpStatusCode status, HttpHeaders headers) {}

    private static Answer handshake(int port, String token) {
        RestClient.RequestBodySpec request = RestClient.create()
                .post()
                .uri("http://localhost:%d/mcp".formatted(port))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Accept", "application/json, text/event-stream");

        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.body(HANDSHAKE)
                .exchange((sent, received) -> new Answer(received.getStatusCode(), received.getHeaders()), false);
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"mcagents.bot-link.port=0", "mcagents.auth.token="})
    class WithNoTokenConfigured {

        /*
        By name, not by type. A bean whose type is List<X> is invisible to injection by type:
        Spring reads that as "every X bean collected", and there are no individual ones.
        */
        @Autowired
        private ApplicationContext context;

        @LocalServerPort
        private int port;

        /* The open default is for a laptop. It has to start, or the warning reaches nobody. */
        @Test
        void theServerStartsAndAnswersWithoutOne() {
            assertEquals(HttpStatus.OK, handshake(port, null).status());
        }

        @Test
        void everyToolInTheCatalogueIsRegistered() {
            List<?> tools = context.getBean("mcAgentTools", List.class);

            assertFalse(tools.isEmpty());
            assertEquals(Catalog.load().size(), tools.size());
            assertInstanceOf(McpServerFeatures.SyncToolSpecification.class, tools.get(0));
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"mcagents.bot-link.port=0", "mcagents.auth.token=letmein"})
    class WithAToken {

        @LocalServerPort
        private int port;

        @Test
        void theRightTokenGetsThroughAndNothingElseDoes() {
            assertEquals(HttpStatus.OK, handshake(port, "letmein").status());
            assertEquals(HttpStatus.UNAUTHORIZED, handshake(port, "wrong").status());
            assertEquals(HttpStatus.UNAUTHORIZED, handshake(port, null).status());
        }

        @Test
        void aRefusalSaysHowToAuthenticate() {
            assertTrue(handshake(port, null).headers().getFirst("WWW-Authenticate").startsWith("Bearer "));
        }

        /* A probe cannot carry a credential, and there is nothing behind it worth reaching. */
        @Test
        void theProbesAreNotBehindIt() {
            HttpStatusCode status = RestClient.create()
                    .get()
                    .uri("http://localhost:%d/actuator/health/liveness".formatted(port))
                    .exchange((sent, received) -> received.getStatusCode(), false);

            assertEquals(HttpStatus.OK, status);
        }
    }
}
