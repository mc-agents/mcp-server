package kr.junhyung.mcagents.tool;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import kr.junhyung.mcagents.bot.BotLink;
import kr.junhyung.mcagents.bot.BotRegistry;
import kr.junhyung.mcagents.bot.BotSession;
import kr.junhyung.mcagents.catalog.Catalog;
import kr.junhyung.mcagents.protocol.Frame;
import kr.junhyung.mcagents.protocol.FrameCodec;
import kr.junhyung.mcagents.protocol.Messages;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A bot on a loopback link that plays a world.
 *
 * <p>The bot holds the chunks a client would: those around where it last stood, and no others. A
 * read-region for a box it does not hold answers with the blocks missing and dropped from the
 * runs, as the real bots do; a /tp moves where it stands; a /fill changes the world it plays; a
 * server with WorldEdit answers the selection and //set, and one with CraftEngine lists its custom
 * blocks, places them as the look they wear, and names the id it files them under. What the tests
 * check is the driving, since the reading itself is the bots' and is proved in their own suites.
 */
final class PlayedWorld implements AutoCloseable {

    /** How far around where it stands the played client holds chunks. */
    static final int HELD_RADIUS = 48;

    static final String BOT = "fab";

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2);
    private final ServerSocket listener;
    private final Socket botSide;

    final Catalog catalog;
    final BotRegistry bots = new BotRegistry(2);
    final BotSession bot;

    /** The world the bot plays, a block a position, air where nothing was put. */
    final Map<Long, String> world = new ConcurrentHashMap<>();

    /** What the server says in chat for a command, by its first word; set to override what is played. */
    final Map<String, Function<String, List<String>>> saysAbout = new ConcurrentHashMap<>();

    /** What the bot was asked to run, in order. */
    final List<String> ran = new CopyOnWriteArrayList<>();

    /** Whether the played server has WorldEdit: answers the selection and //set, or knows neither. */
    volatile boolean worldEdit;

    /** Whether its WorldEdit describes the selection on the CUI channel, which read-selection reads. */
    volatile boolean cui;

    /**
     * How long the played plugin takes to move a corner, as one that handles commands on threads of
     * its own does. Zero is the corner landing while the command is being answered.
     */
    volatile long selectionLagMs;

    /** The played server's CraftEngine custom blocks: what each is called, and the vanilla state it looks like. */
    final Map<String, String> customBlocks = new LinkedHashMap<>();

    volatile int standX = 5;
    volatile int standZ = 5;

    private final String[] selection = new String[2];

    PlayedWorld(Catalog catalog) throws IOException {
        this.catalog = catalog;
        listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        listener.setSoTimeout(10_000);
        botSide = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
        BotLink link = new BotLink(listener.accept(), mapper, timers);
        bot = new BotSession(BOT, "fabric", link, timers);
        bot.accept(new Messages.Status("ready", 1, "paper:25565", BOT, "26.1.2", "Paper", "creative",
                "overworld", new Messages.Position(5.5, 64, 5.5), 20.0, 20.0, false, null, null, null));
        bot.acceptCapabilities(List.of(
                new Messages.Capability("run-command", catalog.require("run-command").wireSchemaHash()),
                new Messages.Capability("read-region", catalog.require("read-region").wireSchemaHash()),
                new Messages.Capability("get-position", catalog.require("get-position").wireSchemaHash()),
                new Messages.Capability("complete-command", catalog.require("complete-command").wireSchemaHash()),
                new Messages.Capability("read-selection", catalog.require("read-selection").wireSchemaHash())));
        bots.add(bot);

        Thread.ofVirtual().start(() -> link.pump(new BotLink.Sink() {
            @Override public void event(Messages.Event event) {}
            @Override public void status(Messages.Status status) {}
            @Override public void log(Messages.Log log) {}
        }));
        Thread.ofVirtual().start(this::play);
    }

    @Override
    public void close() throws IOException {
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
                    case "complete-command" -> complete(call);
                    case "read-selection" -> described(call);
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
        } else if (worldEdit && "//pos1".equals(words[0])) {
            select(0, words[1]);
            says("system", "First position set to (" + words[1].replace(",", ", ") + ").");
        } else if (worldEdit && "//pos2".equals(words[0])) {
            select(1, words[1]);
            says("system", "Second position set to (" + words[1].replace(",", ", ") + ").");
        } else if (worldEdit && "//set".equals(words[0])) {
            /*
            On a thread of its own and a moment later, reading the selection then, as
            FastAsyncWorldEdit does: a driver that moves the corners on without waiting has this
            edit land in the next box.
            */
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException interrupted) {
                    return;
                }
                int[] a = corners(selection[0]);
                int[] b = corners(selection[1]);
                int count = 0;
                for (int x = Math.min(a[0], b[0]); x <= Math.max(a[0], b[0]); x++) {
                    for (int y = Math.min(a[1], b[1]); y <= Math.max(a[1], b[1]); y++) {
                        for (int z = Math.min(a[2], b[2]); z <= Math.max(a[2], b[2]); z++) {
                            world.put(key(x, y, z), looksLike(words[1]));
                            count++;
                        }
                    }
                }
                says("system", "(FAWE) Operation completed (%d).".formatted(count));
            });
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
        } else if (!customBlocks.isEmpty() && command.startsWith("craftengine debug setblock ")) {
            /* Silent, as the plugin's is; the block lands as the look it wears. */
            world.put(key(Integer.parseInt(words[3]), Integer.parseInt(words[4]), Integer.parseInt(words[5])),
                    looksLike(words[6]));
        } else if (!customBlocks.isEmpty() && command.startsWith("craftengine debug get-block-internal-id ")) {
            List<String> ids = new ArrayList<>(customBlocks.keySet());
            int index = ids.indexOf(words[3]);
            says("system", index < 0 ? "Unknown block" : "craftengine:custom_" + (100 + index));
        }
        return new Messages.Result(call.id(), true, "Ran /" + command + ".", null, null, null, 1);
    }

    /** A custom block placed by name or by its filed id lands as the vanilla state it looks like; anything else lands as itself. */
    private String looksLike(String block) {
        if (block.startsWith("craftengine:custom_")) {
            int index = Integer.parseInt(block.substring("craftengine:custom_".length())) - 100;
            List<String> ids = new ArrayList<>(customBlocks.keySet());
            return index >= 0 && index < ids.size() ? customBlocks.get(ids.get(index)) : block;
        }
        return customBlocks.getOrDefault(block, block);
    }

    /**
     * A corner moving, as the plugin moves one: at once, or on a thread of its own a moment after
     * it answered the command, which is what a driver that does not check the selection walks into.
     */
    private void select(int index, String corner) {
        if (selectionLagMs <= 0) {
            selection[index] = corner;
            return;
        }
        timers.schedule(() -> selection[index] = corner, selectionLagMs, TimeUnit.MILLISECONDS);
    }

    /**
     * What a bot answers read-selection with: the selection as the plugin has it, or nothing at all
     * where the plugin does not describe one.
     */
    private Messages.Result described(Messages.Call call) {
        if (!worldEdit || !cui) {
            return new Messages.Result(call.id(), true, "no selection was described",
                    Map.of("supported", false, "points", List.of()), null, null, 1);
        }

        List<Map<String, Object>> points = new ArrayList<>();

        for (int index = 0; index < selection.length; index++) {
            String corner = selection[index];

            if (corner == null) {
                continue;
            }

            int[] at = corners(corner);

            points.add(Map.of("index", index, "x", at[0], "y", at[1], "z", at[2]));
        }
        return new Messages.Result(call.id(), true, "selection described",
                Map.of("supported", true, "shape", "cuboid", "points", points), null, null, 1);
    }

    /**
     * What the played server completes, as CraftEngine's does: its custom blocks after the debug
     * command, matched anywhere in the name and a thousand at most; and after a WorldEdit pattern,
     * the namespaces in use with a colon after each. A prefix nothing matches is answered with
     * nothing, as the real server answers it.
     */
    private Messages.Result complete(Messages.Call call) {
        String text = String.valueOf(call.args().get("text"));
        int limit = call.args().get("limit") instanceof Number number ? number.intValue() : 60;
        String debug = "/craftengine debug get-block-internal-id ";
        List<String> matching = new ArrayList<>();

        if (text.startsWith(debug) && !customBlocks.isEmpty()) {
            String typed = text.substring(debug.length());
            for (String id : customBlocks.keySet()) {
                if (id.contains(typed)) {
                    matching.add(id);
                }
            }
        } else if (text.equals("//set ") && worldEdit) {
            matching.add("minecraft:");
            matching.add("craftengine:");
            customBlocks.keySet().stream().map(id -> id.substring(0, id.indexOf(':') + 1)).distinct().forEach(matching::add);
        }
        if (matching.isEmpty()) {
            return new Messages.Result(call.id(), false, "the server did not answer what completes " + text, null, null,
                    new Messages.Failure("timeout", null, "no answer", true, null), 1);
        }

        int total = Math.min(matching.size(), 1000);
        List<Map<String, Object>> completions = new ArrayList<>();

        for (String name : matching.subList(0, Math.min(total, limit))) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", name);
            one.put("tooltip", null);
            completions.add(one);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", text);
        data.put("total", total);
        data.put("completions", completions);

        return new Messages.Result(call.id(), true, "completed", data, null, null, 1);
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

    private static int[] corners(String written) {
        String[] parts = written.split(",");
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
    }

    static long key(int x, int y, int z) {
        return ((long) x << 40) ^ ((long) (y & 0xfffff) << 20) ^ (z & 0xfffff);
    }

    /** One chat line, from the server itself or from whoever the source names. */
    void says(String source, String line) {
        long seq = bot.feed("chat").nextSeq();
        bot.accept(new Messages.Event(seq, "chat", source, line, List.of(), null, null, seq, seq, 1, false));
    }

    void fill(Region box, String block) {
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    world.put(key(x, y, z), block);
                }
            }
        }
    }

    List<String> commands(String word) {
        return ran.stream().filter(command -> command.startsWith(word + " ")).toList();
    }
}
