package kr.junhyung.mcagents.fixture;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * A menu the way a plugin draws one: a chest with an item in it that no click is allowed to move.
 *
 * <p>The client moves the item as it sends the click and the server puts it back. A bot that answers
 * with what the client predicted says the item was picked up.
 */
final class LockedMenu implements Listener {

    /** Where the item sits, and what it is. */
    static final int SLOT = 5;

    private static final class Holder implements InventoryHolder {

        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    void open(CommandSender sender, Player player) {
        Holder holder = new Holder();
        holder.inventory = Bukkit.createInventory(holder, 27, Component.text("Locked Menu"));
        holder.inventory.setItem(SLOT, new ItemStack(Material.EMERALD));
        player.openInventory(holder.inventory);
        sender.sendMessage("opened the locked menu for " + player.getName());
    }

    @EventHandler
    void click(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }
}
