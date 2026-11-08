package kr.junhyung.mcagents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * What a rollout does to the agents on the server it stops.
 *
 * <p>Each case has its own context rather than a shared one, since closing it is the test.
 */
class ShutdownTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private ConfigurableApplicationContext context;

    private URI mcp;

    private String session;

    @BeforeEach
    void start() throws Exception {
        context = new SpringApplicationBuilder(Main.class)
                .run("--server.port=0", "--mcagents.bot-link.port=0", "--mcagents.auth.token=",
                        // The stream's headers go out with its first event, and a ping is the first.
                        "--spring.ai.mcp.server.streamable-http.keep-alive-interval=1s");
        mcp = URI.create("http://localhost:%d/mcp"
                .formatted(((WebServerApplicationContext) context).getWebServer().getPort()));

        session = post(null, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
                 "capabilities":{},"clientInfo":{"name":"test","version":"0"}}}""")
                .headers().firstValue("Mcp-Session-Id").orElseThrow();
        post(session, """
                {"jsonrpc":"2.0","method":"notifications/initialized"}""");
    }

    @AfterEach
    void stop() {
        context.close();
    }

    /**
     * The stream used to hold Tomcat's drain for its full thirty seconds with nothing answering, and
     * agents gave up reconnecting before the next pod was up.
     */
    @Test
    void anOpenListeningStreamDoesNotHoldTheShutdown() throws Exception {
        HttpResponse<InputStream> stream = HTTP.send(HttpRequest.newBuilder(mcp)
                .header("Mcp-Session-Id", session)
                .header("Accept", "text/event-stream")
                .GET()
                .build(), HttpResponse.BodyHandlers.ofInputStream());
        CompletableFuture<Void> ended = CompletableFuture.runAsync(() -> {
            try (InputStream body = stream.body()) {
                body.transferTo(OutputStream.nullOutputStream());
            } catch (Exception ignored) {
                // A reset is as much an end as a clean close.
            }
        });

        long started = System.nanoTime();
        context.close();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertTrue(took.compareTo(Duration.ofSeconds(10)) < 0, "stopping took " + took);
        ended.get(5, TimeUnit.SECONDS);
    }

    /** Ending the streams must not end the work: a call already running still gets its answer. */
    @Test
    void aToolCallAlreadyRunningIsAnsweredWhileTheServerStops() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CountDownLatch reached = new CountDownLatch(1);
            Thread.ofVirtual().start(() -> {
                try (Socket pinged = silent.accept()) {
                    reached.countDown();
                    pinged.getInputStream().transferTo(OutputStream.nullOutputStream());
                } catch (Exception ignored) {
                    // The ping giving up and hanging up is how this ends.
                }
            });
            CompletableFuture<HttpResponse<String>> call = HTTP.sendAsync(request(session, """
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ping-server",
                     "arguments":{"host":"127.0.0.1","port":%d,"timeoutMs":2000}}}""".formatted(silent.getLocalPort())),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(reached.await(5, TimeUnit.SECONDS), "the ping never reached the listener");

            context.close();

            HttpResponse<String> answer = call.get(5, TimeUnit.SECONDS);
            assertEquals(200, answer.statusCode());
            assertTrue(answer.body().contains("\"id\":2") && answer.body().contains("\"result\""), answer.body());
        }
    }

    private HttpResponse<Void> post(String session, String body) throws Exception {
        return HTTP.send(request(session, body), HttpResponse.BodyHandlers.discarding());
    }

    private HttpRequest request(String session, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(mcp)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) {
            request.header("Mcp-Session-Id", session);
        }
        return request.build();
    }
}
