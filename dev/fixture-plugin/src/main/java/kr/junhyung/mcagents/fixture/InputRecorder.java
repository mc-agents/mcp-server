package kr.junhyung.mcagents.fixture;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Input;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Counts what a player's keys sent: a press of jump, sneak or sprint, the hotbar slot chosen, and a
 * click into the air with either button.
 *
 * <p>Rising edges, because that is what a game driven by keys reacts to. The client sends its whole
 * input every time any key changes, so a held jump arrives again when sneak goes down and would be
 * counted twice by anything reading the flags alone.
 */
final class InputRecorder implements Listener {

    private static final Input NOTHING_HELD = new Input() {
        @Override public boolean isForward() { return false; }
        @Override public boolean isBackward() { return false; }
        @Override public boolean isLeft() { return false; }
        @Override public boolean isRight() { return false; }
        @Override public boolean isJump() { return false; }
        @Override public boolean isSneak() { return false; }
        @Override public boolean isSprint() { return false; }
    };

    private final FixturePlugin.Scores scores;
    private final Conversation conversation;
    private final Map<UUID, Input> last = new ConcurrentHashMap<>();

    InputRecorder(FixturePlugin.Scores scores, Conversation conversation) {
        this.scores = scores;
        this.conversation = conversation;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void input(PlayerInputEvent event) {
        Player player = event.getPlayer();
        Input now = event.getInput();
        Input before = last.getOrDefault(player.getUniqueId(), NOTHING_HELD);
        last.put(player.getUniqueId(), now);

        if (now.isJump() && !before.isJump()) {
            scores.add("fx_jump", player.getName());
            conversation.jumped(player);
        }
        if (now.isSneak() && !before.isSneak()) {
            scores.add("fx_sneak", player.getName());
            conversation.sneaked(player);
        }
        if (now.isSprint() && !before.isSprint()) {
            scores.add("fx_sprint", player.getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void slot(PlayerItemHeldEvent event) {
        scores.set("fx_slot", event.getPlayer().getName(), event.getNewSlot());
        scores.add("fx_slots", event.getPlayer().getName());
        conversation.chose(event.getPlayer(), event.getNewSlot());
    }

    /** Main hand only: an empty main hand makes the client try the off-hand too, which is one click. */
    @EventHandler(priority = EventPriority.MONITOR)
    void click(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            return;
        }
        if (event.getAction() == Action.LEFT_CLICK_AIR) {
            scores.add("fx_left", event.getPlayer().getName());
        } else if (event.getAction() == Action.RIGHT_CLICK_AIR) {
            scores.add("fx_right", event.getPlayer().getName());
        }
    }

    /** Every swing, a left-click on a block or an entity included, which the air clicks leave out. */
    @EventHandler(priority = EventPriority.MONITOR)
    void swing(PlayerArmSwingEvent event) {
        scores.add("fx_swing", event.getPlayer().getName());
    }

    @EventHandler
    void quit(PlayerQuitEvent event) {
        last.remove(event.getPlayer().getUniqueId());
    }
}
