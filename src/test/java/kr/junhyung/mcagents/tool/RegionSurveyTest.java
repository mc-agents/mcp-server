package kr.junhyung.mcagents.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import kr.junhyung.mcagents.bot.BotLink;
import kr.junhyung.mcagents.bot.BotRegistry;
import kr.junhyung.mcagents.bot.BotSession;
import kr.junhyung.mcagents.catalog.Catalog;
import kr.junhyung.mcagents.catalog.ToolSpec;
import kr.junhyung.mcagents.protocol.Frame;
import kr.junhyung.mcagents.protocol.FrameCodec;
import kr.junhyung.mcagents.protocol.Messages;
import kr.junhyung.mcagents.render.RegionRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The walk, against a bot on a loopback link that plays a world.
 *
 * <p>The bot holds the chunks a client would: those around where it last stood, and no others. A
 * read-region for a box it does not hold answers with the blocks missing and dropped from the
 * runs, as the real bots do; a /tp moves where it stands; a /fill changes the world it plays.
 * What is being checked is the driving -- which tiles the bot is sent to, what it is asked to
 * read, what is laid where, what is put down and what is read back -- since the reading itself is
 * the bots' and is proved in their own suites.
 */
class RegionSurveyTest {

    /** How far around where it stands the played client holds chunks. */
    private static final int HELD_RADIUS = 48;

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2);
    private final Catalog catalog = Catalog.load();
    private final BotRegistry bots = new BotRegistry(2);
    private final RemoteTools remote = new RemoteTools(catalog);
    private final RegionStore store = new RegionStore();
    private final RegionSurvey survey = new RegionSurvey(remote, new Commands(remote, catalog), store, catalog);

    /** The world the bot plays, a block a position, air where nothing was put. */
    private final Map<Long, String> world = new ConcurrentHashMap<>();

    /** What the server says in chat for a command, by its first word, and what the bot was asked to run. */
    private final Map<String, Function<String, List<String>>> saysAbout = new ConcurrentHashMap<>();
    private final List<String> ran = new CopyOnWriteArrayList<>();

    private volatile int standX = 5;
    private volatile int standZ = 5;

    private ServerSocket listener;
    private Socket botSide;
    private BotSession bot;

    @BeforeEach
    void link() throws IOException {
        listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        listener.setSoTimeout(10_000);
        botSide = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
        BotLink link = new BotLink(listener.accept(), mapper, timers);
        bot = new BotSession("fab", "fabric", link, timers);
        bot.accept(new Messages.Status("ready", 1, "paper:25565", "fab", "26.1.2", "Paper", "creative",
                "overworld", new Messages.Position(5.5, 64, 5.5), 20.0, 20.0, false, null, null, null));
        bot.acceptCapabilities(List.of(
                new Messages.Capability("run-command", catalog.require("run-command").wireSchemaHash()),
                new Messages.Capability("read-region", catalog.require("read-region").wireSchemaHash()),
                new Messages.Capability("get-position", catalog.require("get-position").wireSchemaHash())));
        bots.add(bot);

        Thread.ofVirtual().start(() -> link.pump(new BotLink.Sink() {
            @Override public void event(Messages.Event event) {}
            @Override public void status(Messages.Status status) {}
            @Override public void log(Messages.Log log) {}
        }));
        Thread.ofVirtual().start(this::play);
    }

    @AfterEach
    void unlink() throws IOException {
        bot.close("test over");
        botSide.close();
        listener.close();
        timers.shutdownNow();
    }

    private void play() {
        try {
            while (true) {
                Frame.Json frame = assertInstanceOf(Frame.Json.class, FrameCodec.read(botSide.getInputStream()));
                Messages.Call call = assertInstanceOf(Messages.Call.class,
                        mapper.readValue(frame.payload(), Messages.ToBot.class));
                Messages.Result result = switch (call.tool()) {
                    case "run-command" -> command(call);
                    case "read-region" -> read(call);
                    case "get-position" -> new Messages.Result(call.id(), true, "here",
                            Map.of("position", Map.of("x", standX, "y", 64, "z", standZ)), null, null, 1);
                    default -> new Messages.Result(call.id(), false, "no such tool", null, null,
                            new Messages.Failure("unsupported", null, "no such tool", false, null), 1);
                };
                FrameCodec.write(botSide.getOutputStream(), new Frame.Json(mapper.writeValueAsBytes(result)));
            }
        } catch (IOException closed) {
            // The test is over.
        }
    }

    private Messages.Result command(Messages.Call call) {
        String command = String.valueOf(call.args().get("command"));
        String[] words = command.split(" ");

        ran.add(command);

        Function<String, List<String>> answer = saysAbout.get(words[0]);

        if (answer != null) {
            answer.apply(command).forEach(line -> says("system", line));
        } else if ("tp".equals(words[0])) {
            standX = (int) Double.parseDouble(words[1]);
            standZ = (int) Double.parseDouble(words[3]);
            says("system", "Teleported fab to " + words[1] + ", " + words[2] + ", " + words[3]);
        } else if ("fill".equals(words[0])) {
            int count = 0;
            for (int x = Integer.parseInt(words[1]); x <= Integer.parseInt(words[4]); x++) {
                for (int y = Integer.parseInt(words[2]); y <= Integer.parseInt(words[5]); y++) {
                    for (int z = Integer.parseInt(words[3]); z <= Integer.parseInt(words[6]); z++) {
                        world.put(key(x, y, z), words[7]);
                        count++;
                    }
                }
            }
            says("system", "Successfully filled %d block(s)".formatted(count));
        }
        return new Messages.Result(call.id(), true, "Ran /" + command + ".", null, null, null, 1);
    }

    /** What a real bot answers: runs of what it holds, in y-then-z-then-x order, the rest counted missing. */
    private Messages.Result read(Messages.Call call) {
        Map<?, ?> from = (Map<?, ?>) call.args().get("from");
        Map<?, ?> to = (Map<?, ?>) call.args().get("to");
        int x1 = ((Number) from.get("x")).intValue();
        int y1 = ((Number) from.get("y")).intValue();
        int z1 = ((Number) from.get("z")).intValue();
        int x2 = ((Number) to.get("x")).intValue();
        int y2 = ((Number) to.get("y")).intValue();
        int z2 = ((Number) to.get("z")).intValue();
        List<String> palette = new ArrayList<>();
        List<Map<String, Integer>> runs = new ArrayList<>();
        int missing = 0;
        int last = -1;
        int count = 0;

        for (int y = y1; y <= y2; y++) {
            for (int z = z1; z <= z2; z++) {
                for (int x = x1; x <= x2; x++) {
                    if (Math.abs(x - standX) > HELD_RADIUS || Math.abs(z - standZ) > HELD_RADIUS) {
                        missing++;
                        continue;
                    }
                    String block = world.getOrDefault(key(x, y, z), "air");
                    int entry = palette.indexOf(block);
                    if (entry < 0) {
                        entry = palette.size();
                        palette.add(block);
                    }
                    if (entry != last && count > 0) {
                        runs.add(Map.of("block", last, "count", count));
                        count = 0;
                    }
                    last = entry;
                    count++;
                }
            }
        }
        if (count > 0) {
            runs.add(Map.of("block", last, "count", count));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", Map.of("x", x1, "y", y1, "z", z1));
        data.put("to", Map.of("x", x2, "y", y2, "z", z2));
        data.put("size", Map.of("x", x2 - x1 + 1, "y", y2 - y1 + 1, "z", z2 - z1 + 1));
        data.put("blocks", (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1));
        data.put("palette", palette);
        data.put("runs", runs);
        data.put("missing", missing);
        data.put("outside", 0);

        return new Messages.Result(call.id(), true, "read", data, null, null, 1);
    }

    private static long key(int x, int y, int z) {
        return ((long) x << 40) ^ ((long) (y & 0xfffff) << 20) ^ (z & 0xfffff);
    }

    private void says(String source, String line) {
        long seq = bot.feed("chat").nextSeq();
        bot.accept(new Messages.Event(seq, "chat", source, line, List.of(), null, null, seq, seq, 1, false));
    }

    private void fill(Region box, String block) {
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    world.put(key(x, y, z), block);
                }
            }
        }
    }

    private static Map<String, Object> corner(int x, int y, int z) {
        return Map.of("x", x, "y", y, "z", z);
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    private List<String> commands(String word) {
        return ran.stream().filter(command -> command.startsWith(word + " ")).toList();
    }

    /**
     * Two tiles wide and one call tall: the bot holds neither where it stands, is sent to the middle
     * of each in turn, reads it, and is put back. What it read is one region with both tiles' blocks
     * where the world had them and one palette across both.
     */
    @Test
    void aBoxPastOneCallIsWalkedTileByTileAndKeptWhole() {
        fill(new Region(200, 64, 200, 263, 71, 263), "stone");
        fill(new Region(264, 64, 200, 327, 71, 263), "dirt");
        world.put(key(300, 70, 250), "gold_block");

        ToolSpec spec = catalog.require("read-region");
        String read = text(survey.read(spec, bot, Map.of("bot", "fab", "name", "yard",
                "from", corner(200, 64, 200), "to", corner(327, 71, 263)), Progress.NONE));

        assertEquals(List.of("tp 232 72 232", "tp 296 72 232", "tp 5.50 64.00 5.50"), commands("tp"));
        assertTrue(read.startsWith("(200, 64, 200) to (327, 71, 263), 128 x 8 x 64, 65536 blocks.\n"
                + "  32768 stone (50%)\n  32767 dirt (50%)\n      1 gold_block (0%)"), read);
        assertTrue(read.contains("No map: 65536 blocks is more than the 4096 one is drawn for."), read);
        assertTrue(read.contains("Kept as region r-"), read);
        assertTrue(read.contains("(\"yard\"), read in 2 tile(s) in "), read);
        assertFalse(read.contains("unread"), read);

        Snapshot kept = store.all().getFirst();

        assertEquals(0, kept.unread());
        assertEquals("gold_block", kept.blockAt(300, 70, 250));
        assertEquals("stone", kept.blockAt(263, 64, 263));
        assertEquals("dirt", kept.blockAt(264, 64, 263));
    }

    /** A box the bot already holds is read where it stands, and the bot is not moved at all. */
    @Test
    void aBoxTheBotAlreadyHoldsIsReadWithoutMovingIt() {
        fill(new Region(0, 64, 0, 40, 64, 40), "stone");

        String read = text(survey.read(catalog.require("read-region"), bot, Map.of("bot", "fab",
                "from", corner(0, 64, 0), "to", corner(40, 71, 40)), Progress.NONE));

        assertEquals(List.of(), commands("tp"));
        assertTrue(read.contains("Kept as region r-"), read);
        assertEquals(41L * 41 * 8, store.all().getFirst().blocks());
    }

    /**
     * A bot that may not /tp is told so in terms of what to do instead, and what it never reached is
     * left unread rather than read as air: the tile it stood in is in the region, the rest is not.
     */
    @Test
    void aBotThatMayNotTeleportIsToldSoAndTheRestIsLeftUnread() {
        saysAbout.put("tp", command -> List.of("Unknown command. Type \"/help\" for help."));

        String read = text(survey.read(catalog.require("read-region"), bot, Map.of("bot", "fab",
                "from", corner(200, 64, 200), "to", corner(327, 71, 263)), Progress.NONE));

        assertEquals(List.of("tp 232 72 232"), commands("tp"));
        assertTrue(read.contains("could not be teleported to (232, 72, 232): /tp came back as \"Unknown command."), read);
        assertTrue(read.contains("needs the permission to run /tp (op)"), read);
        assertTrue(read.contains("65536 (unread)"), read);
    }

    /**
     * Putting a region down is one /fill a box of the mesh, and the world read back is what the
     * region said. The gold block keeps the floor from being one command, which is what shows the
     * mesh was used rather than a /fill a block.
     */
    @Test
    void aKeptRegionIsPutDownWithOneFillABoxAndReadBack() {
        Region box = new Region(0, 64, 0, 3, 64, 3);
        List<RegionRenderer.View.Run> runs = List.of(new RegionRenderer.View.Run(0, 15), new RegionRenderer.View.Run(1, 1));
        store.keep(Snapshot.blank("r-f100", "floor", box, Instant.EPOCH, "import-region")
                .with(box, List.of("stone", "gold_block"), runs));

        String put = text(survey.write(catalog.require("write-region"), bot, Map.of("bot", "fab", "region", "r-f100"),
                Progress.NONE));

        assertEquals(List.of("fill 0 64 0 3 64 2 stone", "fill 0 64 3 2 64 3 stone", "fill 3 64 3 3 64 3 gold_block"),
                commands("fill"));
        assertEquals(List.of(), commands("tp"));
        assertTrue(put.startsWith("Put region r-f100 down over (0, 64, 0) to (3, 64, 3), 4 x 1 x 4, 16 blocks: 16 blocks in 3 /fill command(s) across 1 tile(s)"), put);
        assertTrue(put.contains("Read back: all 16 blocks are as the region has them."), put);
        assertEquals("gold_block", world.get(key(3, 64, 3)));
    }

    /** Moved, with air put down too: the air clears what was there, and the readback says so when it did not. */
    @Test
    void aRegionPutDownElsewhereWithAirClearsWhatWasThereAndTheReadbackSaysWhatDiffers() {
        Region box = new Region(0, 64, 0, 1, 64, 0);
        store.keep(Snapshot.blank("r-f200", null, box, Instant.EPOCH, "import-region")
                .with(box, List.of("stone", "air"), List.of(new RegionRenderer.View.Run(0, 1), new RegionRenderer.View.Run(1, 1))));
        fill(new Region(10, 64, 10, 11, 64, 10), "dirt");
        /* Answered as landed, and not landing: what the readback is for. */
        saysAbout.put("fill", command -> List.of("Successfully filled 1 block(s)"));

        String put = text(survey.write(catalog.require("write-region"), bot, Map.of("bot", "fab", "region", "r-f200",
                "at", corner(10, 64, 10), "pasteAir", true), Progress.NONE));

        assertEquals(List.of("fill 10 64 10 10 64 10 stone", "fill 11 64 10 11 64 10 air"), commands("fill"));
        assertTrue(put.contains("air included"), put);
        assertTrue(put.contains("Read back: 2 of 2 blocks differ from the region, for example (10, 64, 10) wanted stone and is dirt; (11, 64, 10) wanted air and is dirt."), put);
    }

    /** The first /fill is where a bot without the permission finds out, and nothing else is sent after it. */
    @Test
    void aFirstFillThatIsRefusedStopsTheWriteAndSaysWhatItNeeds() {
        Region box = new Region(0, 64, 0, 3, 64, 3);
        store.keep(Snapshot.blank("r-f300", null, box, Instant.EPOCH, "import-region")
                .with(box, List.of("stone", "gold_block"), List.of(new RegionRenderer.View.Run(0, 15), new RegionRenderer.View.Run(1, 1))));
        saysAbout.put("fill", command -> List.of("Unknown command. Type \"/help\" for help."));

        McpSchema.CallToolResult put = survey.write(catalog.require("write-region"), bot,
                Map.of("bot", "fab", "region", "r-f300"), Progress.NONE);

        assertTrue(put.isError());
        assertEquals(1, commands("fill").size());
        assertTrue(text(put).contains("needs the permission to run it (op)"), text(put));
    }

    /** A region nobody kept is refused before the bot is touched. */
    @Test
    void anUnknownRegionIsRefusedBeforeTheBotIsTouched() {
        IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> survey.write(catalog.require("write-region"), bot, Map.of("bot", "fab", "region", "r-none"), Progress.NONE));

        assertTrue(refused.getMessage().contains("no region is kept as \"r-none\""), refused.getMessage());
        assertEquals(List.of(), ran);
    }
}
