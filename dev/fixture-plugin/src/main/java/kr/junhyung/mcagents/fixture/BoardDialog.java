package kr.junhyung.mcagents.fixture;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * hyperfarm's vellum screens: a dialog whose body is the whole menu. Its tabs, its close mark and
 * its rows are runs of text with a callback on them, a tooltip is a hover event on a run, and an
 * item sits in the body with its own tooltip. There is no button below it to press by label.
 *
 * <p>Each tab sends the board again on its own page, which is how a plugin answers a click, and the
 * title says which page came back. Count adds one to {@code fx_board} and sends the same page
 * again; the close mark, a lone glyph as a pack would draw it, closes the dialog for good.
 */
final class BoardDialog {

    private static final ClickCallback.Options REUSABLE = ClickCallback.Options.builder()
        .uses(ClickCallback.UNLIMITED_USES)
        .build();

    private final FixturePlugin.Scores scores;

    BoardDialog(FixturePlugin.Scores scores) {
        this.scores = scores;
    }

    void open(CommandSender sender, Player player) {
        show(player, 1);
        sender.sendMessage("opened the board for " + player.getName());
    }

    private void show(Player player, int page) {
        /* textOfChildren rather than a builder: Adventure 5 changed what build() returns, and 26.1.2 has the old one. */
        Component menu = Component.textOfChildren(
            press(Component.text("[One]"), "Page one", audience -> show(player, 1)),
            Component.text(" "),
            press(Component.text("[Two]"), "Page two", audience -> show(player, 2)),
            Component.text(" "),
            press(Component.text("\uE101"), null, audience -> player.closeDialog()),
            Component.newline(),
            press(Component.text("Count", NamedTextColor.GOLD), null, audience -> {
                scores.add("fx_board", player.getName());
                show(player, page);
            }),
            Component.text(" "),
            Component.text("[?]").hoverEvent(HoverEvent.showText(Component.text("\uE010 120\n\uE011 3"))));

        ItemStack reward = new ItemStack(Material.DIAMOND, 3);
        player.showDialog(Dialog.create(factory -> factory.empty()
            .base(DialogBase.builder(Component.text("Board " + page))
                .canCloseWithEscape(true)
                .body(List.of(
                    DialogBody.plainMessage(menu, 200),
                    DialogBody.item(reward).showTooltip(true).build()))
                .build())
            .type(DialogType.notice())));
    }

    private static Component press(Component text, String tooltip, Consumer<Audience> then) {
        Component pressed = text.clickEvent(ClickEvent.callback(then::accept, REUSABLE));
        return tooltip == null ? pressed : pressed.hoverEvent(HoverEvent.showText(Component.text(tooltip)));
    }
}
