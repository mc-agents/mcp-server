package kr.junhyung.mcagents.fixture;

import net.kyori.adventure.key.Key;
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
 * A paged menu the way hyperfarm draws one: a background glyph and the page number, each in the
 * pack's own font, make the title, and turning the page redraws it under the window's own id -- an
 * open-screen packet for the container the client already has, and then the contents again.
 *
 * <p>The client builds a new menu for that packet and keeps its prediction on the old one. A bot
 * that waited for the server to answer in the menu it clicked waited on one the client had let go
 * of, and one that answered from that menu reported the paper on the cursor.
 */
final class PagedMenu implements Listener {

    static final int ITEM = 0;
    static final int NEXT = 51;

    private static final int SIZE = 54;
    private static final int PAGES = 2;
    private static final Key BACKGROUND = Key.key("mcagents", "ui/background");
    private static final Key PAGE = Key.key("mcagents", "ui/page_6");

    private static final class Holder implements InventoryHolder {

        private int page = 1;
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    void open(CommandSender sender, Player player) {
        Holder holder = new Holder();
        holder.inventory = Bukkit.createInventory(holder, SIZE, title(holder.page));
        holder.inventory.setItem(ITEM, new ItemStack(Material.EMERALD));
        holder.inventory.setItem(NEXT, next());
        player.openInventory(holder.inventory);
        sender.sendMessage("opened the paged menu for " + player.getName());
    }

    private static Component title(int page) {
        return Component.text()
            .append(Component.text("\uE000").font(BACKGROUND))
            .append(Component.text(page + "/" + PAGES).font(PAGE))
            .build();
    }

    /** A button whose name is empty rather than absent, which is how a menu hides an item's own. */
    private static ItemStack next() {
        ItemStack paper = new ItemStack(Material.PAPER);
        paper.editMeta(meta -> meta.customName(Component.empty()));
        return paper;
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
        if (holder.page == 1 && event.getSlot() == NEXT) {
            holder.page = 2;
            holder.inventory.setItem(ITEM, new ItemStack(Material.DIAMOND));
            holder.inventory.clear(NEXT);
            redraw(player, title(holder.page));
        }
    }

    @EventHandler
    void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    /**
     * The title changed the way hyperfarm changes it. The API's own way is a legacy string that
     * cannot carry a font, and the packet is not in the API; the server's classes are on the
     * plugin's class path, the same as {@link FishingBite}.
     */
    private static void redraw(Player player, Component title) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object menu = handle.getClass().getField("containerMenu").get(handle);
            Class<?> menuType = Class.forName("net.minecraft.world.inventory.MenuType");
            Class<?> vanillaComponent = Class.forName("net.minecraft.network.chat.Component");
            Object vanillaTitle = Class.forName("io.papermc.paper.adventure.PaperAdventure")
                .getMethod("asVanilla", Component.class).invoke(null, title);
            Object packet = Class.forName("net.minecraft.network.protocol.game.ClientboundOpenScreenPacket")
                .getConstructor(int.class, menuType, vanillaComponent)
                .newInstance(menu.getClass().getField("containerId").get(menu), menuType.getField("GENERIC_9x6").get(null), vanillaTitle);
            Object connection = handle.getClass().getField("connection").get(handle);
            connection.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"))
                .invoke(connection, packet);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not redraw the title", e);
        }
        player.updateInventory();
    }
}
