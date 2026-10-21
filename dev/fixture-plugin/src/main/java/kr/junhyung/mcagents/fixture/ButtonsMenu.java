package kr.junhyung.mcagents.fixture;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * One row with one button, counting which mouse button pressed it: {@code fx_menu_left} and
 * {@code fx_menu_right}. A menu that does one thing on a left click and another on a right one is
 * common, and the server is the one that can say which arrived.
 *
 * <p>The button sits in the middle slot, 4, so where it is drawn follows from the vanilla layout
 * of a one-row chest and nothing else.
 */
final class ButtonsMenu implements Listener {

    static final int BUTTON = 4;

    private static final class Holder implements InventoryHolder {

        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final FixturePlugin.Scores scores;

    ButtonsMenu(FixturePlugin.Scores scores) {
        this.scores = scores;
    }

    void open(CommandSender sender, Player player) {
        Holder holder = new Holder();
        holder.inventory = Bukkit.createInventory(holder, 9, Component.text("Buttons"));
        holder.inventory.setItem(BUTTON, new ItemStack(Material.STONE_BUTTON));
        player.openInventory(holder.inventory);
        sender.sendMessage("opened the buttons for " + player.getName());
    }

    @EventHandler
    void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()
                || event.getSlot() != BUTTON) {
            return;
        }
        if (event.getClick() == ClickType.LEFT) {
            scores.add("fx_menu_left", player.getName());
        } else if (event.getClick() == ClickType.RIGHT) {
            scores.add("fx_menu_right", player.getName());
        }
    }

    @EventHandler
    void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }
}
