package kr.junhyung.mcagents.e2e;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * mcp-server, as the process a release runs.
 *
 * <p>The jar and not a Spring test context, because what is being checked is the thing that ships:
 * the endpoint it serves, the catalogue it loads from its own resources, and the port bots dial.
 * A test context would share this JVM's classpath and could pass while the jar did not.
 */
final class Server implements AutoCloseable {

    private static final Duration BOOT = Duration.ofMinutes(2);
    private static final Duration POLL = Duration.ofMillis(250);

    private final Process process;
    private final int mcpPort;
    private final int linkPort;

    Server(Path jar) {
        mcpPort = freePort();
        linkPort = freePort();

        try {
            process = new ProcessBuilder(
                    javaBinary(), "-jar", jar.toString(),
                    "--server.port=" + mcpPort,
                    "--mcagents.bot-link.port=" + linkPort,
                    /* A bot that is already linked is the one join-server uses; nothing to create. */
                    "--mcagents.bots.provision=never")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        } catch (IOException failed) {
            throw new IllegalStateException("could not start " + jar, failed);
        }

        awaitHealth();
    }

    int mcpPort() {
        return mcpPort;
    }

    /** Where a bot dials. A container is told this before the server is up, so it is picked first. */
    int linkPort() {
        return linkPort;
    }

    private void awaitHealth() {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest liveness = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + mcpPort + "/actuator/health/liveness"))
            .timeout(Duration.ofSeconds(2))
            .build();
        Instant deadline = Instant.now().plus(BOOT);

        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new IllegalStateException("the server exited with " + process.exitValue());
            }
            try {
                if (client.send(liveness, HttpResponse.BodyHandlers.ofString()).statusCode() == 200) {
                    return;
                }
            } catch (IOException | InterruptedException notYet) {
                /* Still booting. */
            }
            sleep();
        }

        throw new IllegalStateException("the server did not report itself live within " + BOOT);
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /**
     * A port nothing is on. Racy in principle and not in practice: the server takes it within
     * seconds, and the alternative is asking the server what it chose, which a bot container has
     * to be told before the server starts.
     */
    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException failed) {
            throw new IllegalStateException("no free port", failed);
        }
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    @Override
    public void close() {
        process.destroy();

        try {
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
