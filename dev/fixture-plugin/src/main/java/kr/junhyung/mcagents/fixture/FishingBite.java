package kr.junhyung.mcagents.fixture;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * A bite the way hyperfarm's fishing gives one: a splash sound played to the angler, and a catch only
 * for a right-click inside the window after it.
 *
 * <p>The bite comes a fixed time after the cast and whether the hook is in water does not matter,
 * so a case does not have to aim. Vanilla bites take seconds longer than this and are not counted.
 */
final class FishingBite implements Listener {

    /*
    Half this was shorter than a cast's answer and the next call reaching a bot on a loaded machine,
    and the splash went by before the press waiting for it was listening.
    */
    private static final long BITE_AFTER_TICKS = 60;
    private static final int WINDOW_TICKS = 40;

    private final Plugin plugin;
    private final FixturePlugin.Scores scores;
    private final Map<UUID, BukkitTask> waiting = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> bitAt = new ConcurrentHashMap<>();

    FishingBite(Plugin plugin, FixturePlugin.Scores scores) {
        this.plugin = plugin;
        this.scores = scores;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void fish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        switch (event.getState()) {
            case FISHING -> cast(player, event.getHook());
            case BITE, LURED -> { }
            default -> {
                cancel(id);
                Integer bite = bitAt.remove(id);
                if (bite != null && Bukkit.getCurrentTick() - bite <= WINDOW_TICKS) {
                    scores.add("fx_catch", player.getName());
                } else {
                    scores.add("fx_miss", player.getName());
                }
            }
        }
    }

    private void cast(Player player, FishHook hook) {
        UUID id = player.getUniqueId();
        cancel(id);
        bitAt.remove(id);

        waiting.put(id, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            waiting.remove(id);
            if (hook.isValid() && player.isOnline()) {
                /*
                At the angler, not the hook. A sound at volume 1 reaches players within sixteen blocks of
                it, and a rod cast straight up still had its hook twenty blocks overhead when the bite came,
                so the splash was never sent to the one player it was for.
                */
                player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_SPLASH, 1.0F, 1.0F);
                bitAt.put(id, Bukkit.getCurrentTick());
            }
        }, BITE_AFTER_TICKS));
    }

    private void cancel(UUID id) {
        BukkitTask task = waiting.remove(id);
        if (task != null) {
            task.cancel();
        }
    }
}
