package kr.junhyung.mcagents.e2e;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
/**
 * A Minecraft server with the fixture in it, and a bot standing in that world.
 *
 * <p>Everything the comparison and conformance scripts under {@code dev/} needed a person to set up
 * by hand: a server, a datapack, a bot pointed at this process. What they could not do is fail a
 * build, so a renderer that started saying something else went unnoticed until somebody ran them
 * and read the output. This is the same world, started by the test that asserts against it.
 *
 * <p>The datapack is mounted before the server's first boot rather than copied in afterwards. A
 * datapack list is read when the world loads, so copying it in needs a reload, and a dialog
 * definition needs a restart on top of that -- neither of which is worth doing when the world does
 * not exist yet and the mount is simply there when it is created.
 */
final class BotWorld implements AutoCloseable {

    /** The bot's own name, which is also the name join-server is given. */
    static final String BOT = "e2e";

    private static final String FIXTURE = "mcagents";
    private static final String SERVER_ALIAS = "paper";
    private static final int MC_PORT = 25565;

    /** Boot, world generation and a datapack. Slow the first time an image is pulled. */
    private static final Duration SERVER_START = Duration.ofMinutes(5);

    /** A client jar, its libraries and 500MB of assets, unless the volume already holds them. */
    private static final Duration BOT_LINK = Duration.ofMinutes(10);

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> server;
    private final GenericContainer<?> bot;

    BotWorld(Path fixture, String minecraftVersion, String botImage, String botKind, int linkPort) {
        /*
        The bot dials this process, which is not in a container. Host networking would do it on a
        Linux runner and not on a Mac, where a container's localhost is the virtual machine's;
        this is the way that works on both.
        */
        Testcontainers.exposeHostPorts(linkPort);

        server = new GenericContainer<>("itzg/minecraft-server:java25")
            .withEnv("EULA", "TRUE")
            .withEnv("TYPE", "PAPER")
            .withEnv("VERSION", minecraftVersion)
            .withEnv("ONLINE_MODE", "FALSE")
            .withEnv("MODE", "creative")
            .withEnv("LEVEL_TYPE", "minecraft:flat")
            .withEnv("GENERATE_STRUCTURES", "false")
            .withEnv("SPAWN_PROTECTION", "0")
            .withEnv("SPAWN_MONSTERS", "FALSE")
            .withEnv("VIEW_DISTANCE", "6")
            .withEnv("MEMORY", "2G")
            .withEnv("RCON_CMDS_STARTUP", "op " + BOT + "\ndifficulty peaceful\nweather clear 1000000")
            /*
            The image copies it into the world's datapack directory before the server starts, which
            is the only way it lands there owned by the user the server runs as. Mounting it
            straight into the world makes Docker create the world directory owned by root, and the
            server then cannot take its own session lock and exits.
            */
            .withEnv("DATAPACKS", "/fixture")
            .withFileSystemBind(fixture.toString(), "/fixture", BindMode.READ_ONLY)
            .withNetwork(network)
            .withNetworkAliases(SERVER_ALIAS)
            .waitingFor(Wait.forLogMessage(".*RCON running.*\\n", 1).withStartupTimeout(SERVER_START));

        bot = new GenericContainer<>(botImage)
            .withEnv("MCP_SERVER_HOST", "host.testcontainers.internal")
            .withEnv("MCP_SERVER_PORT", String.valueOf(linkPort))
            .withEnv("BOT_NAME", BOT)
            .withEnv("MC_VERSION", minecraftVersion)
            .withNetwork(network)
            /*
            "dialling" and not "tools ready": the mod says the first once the client has finished
            loading, which on a machine without a graphics card is a minute and a half after the
            process starts.
            */
            .waitingFor(Wait.forLogMessage(".*dialling .*\\n", 1).withStartupTimeout(BOT_LINK));

        /*
        Kept between runs, because filling it is a 500MB download and what is in it does not
        change. Named after the image rather than the version: a client's LWJGL natives are built
        for one architecture, so an arm64 image filling a volume an amd64 one then reads leaves the
        client loading natives it cannot run, and the link dies during the join. An azalea bot has
        no client jar and no assets to keep.
        */
        if ("fabric".equals(botKind)) {
            bot.withCreateContainerCmdModifier(command -> command.getHostConfig()
                .withBinds(com.github.dockerjava.api.model.Bind.parse(assets(botImage) + ":/mc")));
        }
    }

    private static String assets(String botImage) {
        return "mc-agents-e2e-" + botImage.replaceAll("[^A-Za-z0-9_.-]", "-");
    }

    /** The server first: a bot that reaches a world before there is one has nothing to join. */
    void start() {
        server.start();
        run("function " + FIXTURE + ":setup");
        /*
        Again once what it forceloads is loaded. At boot nothing keeps the chunks off spawn loaded,
        and whatever the first run put there was not placed -- the pool, which appeared only after
        some case ran setup again, so whether a case stood in water depended on the ones before it.
        */
        awaitLoaded();
        run("function " + FIXTURE + ":setup");
        bot.start();
    }

    private void awaitLoaded() {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (!run("execute if loaded -10 -61 -10").startsWith("Test passed")) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("the fixture's pool never loaded");
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }

    /** Where the bot is told to connect, which is the server's name on the network they share. */
    String minecraftHost() {
        return SERVER_ALIAS;
    }

    int minecraftPort() {
        return MC_PORT;
    }

    /** A command as the console, for putting the world into the state a case needs. */
    String run(String command) {
        try {
            GenericContainer.ExecResult answer = server.execInContainer("rcon-cli", command);
            return answer.getStdout().trim();
        } catch (IOException | InterruptedException failed) {
            throw new IllegalStateException("could not run /" + command, failed);
        }
    }

    /**
     * The end of both logs, which is what a failure needs and what a pass does not.
     *
     * <p>Printed only when something fails: a Minecraft server and a Minecraft client together
     * write thousands of lines a run, and a job whose output is that is a job nobody reads.
     */
    String logs(int lines) {
        return "--- minecraft\n" + tail(server.getLogs(), lines)
            + "\n--- bot\n" + tail(bot.getLogs(), lines)
            + "\n--- crash\n" + crash();
    }

    /**
     * The crash report, when the client left one.
     *
     * <p>A client that dies takes the link with it, and what mcp-server can say about that is
     * "the link to the bot closed". The reason is in a file inside the container, and the tail of
     * the log is the middle of the report's mod list -- which says nothing at all.
     */
    private String crash() {
        try {
            return bot.execInContainer("sh", "-c",
                "head -40 \"$(ls -t ${BOT_WORK_DIR:-/data}/crash-reports/*.txt 2>/dev/null | head -1)\""
                    + " 2>/dev/null || echo none").getStdout();
        } catch (IOException | InterruptedException unreadable) {
            return "(could not be read: " + unreadable.getMessage() + ")";
        }
    }

    private static String tail(String log, int lines) {
        String[] all = log.split("\n");
        return String.join("\n", java.util.Arrays.asList(all)
            .subList(Math.max(all.length - lines, 0), all.length));
    }

    @Override
    public void close() {
        bot.stop();
        server.stop();
        network.close();
    }
}
