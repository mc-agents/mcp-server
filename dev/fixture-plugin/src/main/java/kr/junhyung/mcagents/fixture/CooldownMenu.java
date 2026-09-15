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
 * A menu button with a cooldown, the way a plugin gates a click: the first click on it is taken,
 * a second within sixty ticks is refused, and the button is redrawn either way to say which.
 *
 * <p>What the server measured is kept in objectives: how many clicks were taken and refused, and
 * how many ticks apart the last two arrived. Two clicks made as two tool calls land a second or
 * more apart and are both taken; only a sequence run inside the bot can put twenty ticks between
 * them, and the gap is what says the bot honoured the wait rather than merely clicking twice.
 *
 * <p>Every click is cancelled, so the client's prediction is undone and the server sends the
 * window back with the redrawn button in it, which is the resend a click step waits for.
 */
final class CooldownMenu implements Listener {

    static final int BUTTON = 13;

    private static final int COOLDOWN_TICKS = 60;

    private static final class Holder implements InventoryHolder {

        private Inventory inventory;
        private Integer lastAccepted;
        private Integer lastClicked;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final FixturePlugin.Scores scores;

    CooldownMenu(FixturePlugin.Scores scores) {
        this.scores = scores;
    }

    void open(CommandSender sender, Player player) {
        Holder holder = new Holder();
        holder.inventory = Bukkit.createInventory(holder, 27, Component.text("Tracker"));
        holder.inventory.setItem(BUTTON, named(Material.NAME_TAG, "Untrack"));
        player.openInventory(holder.inventory);
        sender.sendMessage("opened the tracker for " + player.getName());
    }

    @EventHandler
    void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()
                || event.getSlot() != BUTTON) {
            return;
        }
        int now = Bukkit.getCurrentTick();
        scores.set("fx_gap", player.getName(), holder.lastClicked == null ? 0 : now - holder.lastClicked);
        holder.lastClicked = now;

        if (holder.lastAccepted != null && now - holder.lastAccepted < COOLDOWN_TICKS) {
            scores.add("fx_too_soon", player.getName());
            holder.inventory.setItem(BUTTON, named(Material.BARRIER, "Too soon"));
            return;
        }
        holder.lastAccepted = now;
        scores.add("fx_untrack", player.getName());
        holder.inventory.setItem(BUTTON, named(Material.LIME_DYE, "Untracked"));
    }

    @EventHandler
    void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private static ItemStack named(Material material, String name) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> meta.customName(Component.text(name)));
        return stack;
    }
}
