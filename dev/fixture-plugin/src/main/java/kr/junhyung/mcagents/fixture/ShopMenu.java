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
 * A shop the way hyperfarm draws one. Clicking the item in the shop opens a second window, and
 * clicking +1 in that one opens it again with the count moved on. Neither click moves a slot.
 *
 * <p>Both answer a click with a window the click did not happen in. A bot waiting for the server to
 * send back the window it clicked waits for a window that is already gone.
 */
final class ShopMenu implements Listener {

    static final int ITEM = 5;
    static final int PLUS_ONE = 13;
    static final int COUNT = 22;

    private static final class Holder implements InventoryHolder {

        private final int count;
        private Inventory inventory;

        Holder(int count) {
            this.count = count;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    void open(CommandSender sender, Player player) {
        Holder holder = new Holder(-1);
        holder.inventory = Bukkit.createInventory(holder, 27, Component.text("Shop"));
        holder.inventory.setItem(ITEM, new ItemStack(Material.COD));
        player.openInventory(holder.inventory);
        sender.sendMessage("opened the shop for " + player.getName());
    }

    private void stepper(Player player, int count) {
        Holder holder = new Holder(count);
        holder.inventory = Bukkit.createInventory(holder, 27, Component.text("Buy"));
        holder.inventory.setItem(PLUS_ONE, new ItemStack(Material.LIME_DYE));
        holder.inventory.setItem(COUNT, new ItemStack(Material.COD, count));
        player.openInventory(holder.inventory);
    }

    @EventHandler
    void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        if (holder.count < 0 && event.getSlot() == ITEM) {
            stepper(player, 1);
        } else if (holder.count > 0 && event.getSlot() == PLUS_ONE) {
            stepper(player, holder.count + 1);
        }
    }

    @EventHandler
    void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }
}
