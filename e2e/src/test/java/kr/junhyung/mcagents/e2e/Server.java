package kr.junhyung.mcagents.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * mcp-server, as the process a release runs.
 *
 * <p>The jar and not a Spring test context, because what is being checked is the thing that ships:
 * the endpoint it serves, the catalogue it loads from its own resources, and the port bots dial.
 * A test context would share this JVM's classpath and could pass while the jar did not.
 *
 * <p>Both ports are the server's to choose. Picking a free one here and letting the server bind it
 * a moment later leaves a gap something else can take, and two runs in a row was enough to hit it;
 * asking for zero and reading back what it bound has no gap to lose. Nothing needs the numbers
 * before then -- the bot container is built after this returns.
 */
final class Server implements AutoCloseable {

    private static final Duration BOOT = Duration.ofMinutes(2);
    private static final Duration POLL = Duration.ofMillis(250);

    private static final Pattern HTTP = Pattern.compile("Tomcat started on port (\\d+)");
    private static final Pattern LINK = Pattern.compile("listening for bots on port (\\d+)");

    private final Process process;
    private final Path log;
    private final int mcpPort;
    private final int linkPort;

    Server(Path jar) {
        try {
            log = Files.createTempFile("mcp-server", ".log");
            process = new ProcessBuilder(
                    javaBinary(), "-jar", jar.toString(),
                    "--server.port=0",
                    "--mcagents.bot-link.port=0",
                    /* A bot that is already linked is the one join-server uses; nothing to create. */
                    "--mcagents.bots.provision=never")
                .redirectErrorStream(true)
                /* Kept rather than discarded: a server that will not start has to be able to say so. */
                .redirectOutput(log.toFile())
                .start();
        } catch (IOException failed) {
            throw new IllegalStateException("could not start " + jar, failed);
        }

        mcpPort = await(HTTP, "an HTTP port");
        linkPort = await(LINK, "a port for bots");
    }

    int mcpPort() {
        return mcpPort;
    }

    /** Where a bot dials. */
    int linkPort() {
        return linkPort;
    }

    private int await(Pattern pattern, String what) {
        Instant deadline = Instant.now().plus(BOOT);

        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new IllegalStateException(
                    "the server exited with " + process.exitValue() + "\n" + read());
            }

            Matcher found = pattern.matcher(read());

            if (found.find()) {
                return Integer.parseInt(found.group(1));
            }
            sleep();
        }

        throw new IllegalStateException(
            "the server did not report " + what + " within " + BOOT + "\n" + read());
    }

    private String read() {
        try {
            return Files.readString(log);
        } catch (IOException unreadable) {
            return "(its log could not be read: " + unreadable.getMessage() + ")";
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    @Override
    public void close() {
        process.destroy();

        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
