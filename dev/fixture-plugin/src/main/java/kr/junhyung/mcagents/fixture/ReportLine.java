package kr.junhyung.mcagents.fixture;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * hyperfarm's chat report: a whole chat line carries a custom click, and its payload names the
 * message. The server answers the click with the report dialog, so the click reaches nothing on the
 * client and everything it does is the server's.
 *
 * <p>{@code fx_report} counts a click whose payload named the line that was sent. One that arrived
 * with the payload rewritten counts in {@code fx_report_mangled} and opens nothing.
 */
final class ReportLine implements Listener {

    private static final Key CLICK = Key.key("mcagents", "report");

    private static final String MESSAGE = "message";

    private final FixturePlugin.Scores scores;

    private UUID sent;

    ReportLine(FixturePlugin.Scores scores) {
        this.scores = scores;
    }

    void send(CommandSender sender, Player player) {
        sent = UUID.randomUUID();
        player.sendMessage(Component.text("<Steve> anyone selling wheat?")
            .clickEvent(ClickEvent.custom(CLICK, BinaryTagHolder.binaryTagHolder("{" + MESSAGE + ":\"" + sent + "\"}"))));
        sender.sendMessage("sent a reportable line to " + player.getName());
    }

    @EventHandler
    void clicked(PlayerCustomClickEvent event) {
        if (!CLICK.equals(event.getIdentifier()) || !(event.getCommonConnection() instanceof PlayerGameConnection connection)) {
            return;
        }
        Player player = connection.getPlayer();
        DialogResponseView payload = event.getDialogResponseView();
        String message = payload == null ? null : payload.getText(MESSAGE);

        if (sent == null || !sent.toString().equals(message)) {
            scores.add("fx_report_mangled", player.getName());
            return;
        }
        scores.add("fx_report", player.getName());
        player.showDialog(Dialog.create(factory -> factory.empty()
            .base(DialogBase.builder(Component.text("Report chat")).canCloseWithEscape(true).build())
            .type(DialogType.notice())));
    }
}
