package kr.junhyung.mcagents.fixture;

import com.sun.net.httpserver.HttpServer;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/**
 * A dialog body drawn the way a resource pack draws one, where a press can miss.
 *
 * <p>The pack has a 16-pixel tile and the negative spacing a pack positions things with. The first
 * row is two tiles with a pull-back between them, so the second is drawn exactly over the first and
 * takes every press meant for it. The second row has a tile pushed below its line by a negative
 * ascent, so it is drawn outside the widget that would take a press on it. The third is long enough
 * to scroll, with a row at the bottom that is out of view until it is.
 *
 * <p>The pack is served from this server over HTTP and sent to the one player asking, so nothing
 * else in the suite draws with it. The dialog goes up once the client says the pack loaded: shown
 * before, its glyphs are the missing-character box and none of the layout is the pack's.
 */
final class OverlapDialog implements Listener {

    private static final UUID PACK = UUID.fromString("6d636167-656e-7473-2d6f-7665726c6170");

    private static final int PORT = 25580;

    /* The name the end-to-end suite gives the server on the bots' network. */
    private static final String HOST = "paper";

    private static final Key FONT = Key.key("mcagents", "board");

    private static final String TILE = "\uE200";
    private static final String LOW_TILE = "\uE201";

    /* -1, -8 and -8: a tile's advance, which is its 16 pixels and the one after it. */
    private static final String BACK_ONE_TILE = "\uF801\uF808\uF808";

    private static final int FILLER_ROWS = 24;

    private static final ClickCallback.Options REUSABLE = ClickCallback.Options.builder()
        .uses(ClickCallback.UNLIMITED_USES)
        .build();

    private final FixturePlugin.Scores scores;
    private final byte[] pack;
    private final byte[] sha1;

    private HttpServer server;

    OverlapDialog(FixturePlugin.Scores scores) {
        this.scores = scores;
        this.pack = pack();
        this.sha1 = sha1(pack);
    }

    void open(CommandSender sender, Player player) {
        serve();
        player.setResourcePack(PACK, "http://" + HOST + ":" + PORT + "/board.zip", sha1, Component.text("mc-agents board"), false);
        sender.sendMessage("sent the board pack to " + player.getName());
    }

    void unpack(CommandSender sender, Player player) {
        player.removeResourcePacks(PACK);
        sender.sendMessage("took the board pack from " + player.getName());
    }

    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @EventHandler
    void loaded(PlayerResourcePackStatusEvent event) {
        if (PACK.equals(event.getID()) && event.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            show(event.getPlayer());
        }
    }

    private void show(Player player) {
        Component first = Component.textOfChildren(
            glyph(TILE).clickEvent(callback(audience -> scores.add("fx_overlap_under", player.getName()))),
            glyph(BACK_ONE_TILE),
            glyph(TILE).clickEvent(callback(audience -> {
                scores.add("fx_overlap", player.getName());
                show(player);
            })));
        Component second = Component.textOfChildren(
            Component.text("Low "),
            glyph(LOW_TILE).clickEvent(callback(audience -> scores.add("fx_overlap_low", player.getName()))));

        StringBuilder rows = new StringBuilder();
        for (int row = 1; row <= FILLER_ROWS; row++) {
            rows.append("Row ").append(row).append('\n');
        }
        Component third = Component.textOfChildren(
            Component.text(rows.toString()),
            Component.text("Bottom").clickEvent(callback(audience -> {
                scores.add("fx_overlap_bottom", player.getName());
                show(player);
            })));

        player.showDialog(Dialog.create(factory -> factory.empty()
            .base(DialogBase.builder(Component.text("Overlap"))
                .canCloseWithEscape(true)
                .body(List.of(
                    DialogBody.plainMessage(first, 200),
                    DialogBody.plainMessage(second, 200),
                    DialogBody.plainMessage(third, 200)))
                .build())
            .type(DialogType.notice())));
    }

    private static Component glyph(String text) {
        return Component.text(text).font(FONT);
    }

    private static ClickEvent<?> callback(Consumer<Audience> then) {
        return ClickEvent.callback(then::accept, REUSABLE);
    }

    private synchronized void serve() {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(PORT), 0);
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
        server.createContext("/board.zip", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, pack.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(pack);
            }
        });
        server.start();
    }

    /**
     * The tile's ascent of 12 puts most of it on its line. The low tile's ascent of -40 puts the
     * whole of it below, which the pack format allows because only an ascent above the height is
     * refused.
     */
    private static byte[] pack() {
        String meta = """
            {"pack": {"description": "mc-agents board", "min_format": [97, 0], "max_format": 120}}""";
        String font = """
            {"providers": [
              {"type": "space", "advances": {"\\uF801": -1, "\\uF808": -8}},
              {"type": "bitmap", "file": "mcagents:font/tile.png", "ascent": 12, "height": 16, "chars": ["\\uE200"]},
              {"type": "bitmap", "file": "mcagents:font/tile.png", "ascent": -40, "height": 8, "chars": ["\\uE201"]}
            ]}""";

        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipped)) {
            put(zip, "pack.mcmeta", meta.getBytes(StandardCharsets.UTF_8));
            put(zip, "assets/mcagents/font/board.json", font.getBytes(StandardCharsets.UTF_8));
            put(zip, "assets/mcagents/textures/font/tile.png", tile());
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
        return zipped.toByteArray();
    }

    private static byte[] tile() throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, 0xFFFFFFFF);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        return png.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static byte[] sha1(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(content);
        } catch (NoSuchAlgorithmException cause) {
            throw new IllegalStateException(cause);
        }
    }
}
