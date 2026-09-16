package kr.junhyung.mcagents.fixture;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * A bite the way hyperfarm's fishing gives one: the bobber pulled under, a splash sound named by its
 * key and played to the angler on the master channel, and a catch only for a right-click inside the
 * window after it.
 *
 * <p>The bite comes a fixed time after the cast and whether the hook is in water does not matter,
 * so a case does not have to aim. Vanilla's own bite is put off past any case: it sets the hook's
 * biting flag, which a plugin's bite never does, and a bot that only read the flag passed here and
 * waited out every bite on hyperfarm.
 */
final class FishingBite implements Listener {

    /*
    The cast's answer and the next call reaching the bot have to fit in here: at sixty ticks the
    splash still went by, once in some thirty runs on a loaded runner, before the press waiting for
    it was listening, and at thirty it went by often.
    */
    private static final long BITE_AFTER_TICKS = 100;
    private static final int WINDOW_TICKS = 40;

    /** Vanilla's bite, in ticks after the cast: twenty minutes, which no case waits. */
    private static final int VANILLA_WAIT_TICKS = 24_000;

    /** Vanilla pulls a bobber under at 0.24 to 0.4 blocks a tick; hyperfarm copies it. */
    private static final double PULL = -0.3;

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
        hook.setWaitTime(VANILLA_WAIT_TICKS, VANILLA_WAIT_TICKS);

        waiting.put(id, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            waiting.remove(id);
            if (hook.isValid() && player.isOnline()) {
                /*
                At the angler, not the hook. A sound at volume 1 reaches players within sixteen blocks of
                it, and a rod cast straight up still had its hook twenty blocks overhead when the bite came,
                so the splash was never sent to the one player it was for.
                */
                pullUnder(player, hook);
                player.playSound(player.getLocation(), "minecraft:entity.fishing_bobber.splash", SoundCategory.MASTER, 0.5F, 1.0F);
                bitAt.put(id, Bukkit.getCurrentTick());
            }
        }, BITE_AFTER_TICKS));
    }

    /**
     * Sent to the angler as it is, the way hyperfarm sends it. Velocity set on the hook goes through
     * the hook's own tick in the water first, and what reached the client was a fraction of the pull.
     * The packet is not in the API, and the server's classes are on the plugin's class path.
     */
    private static void pullUnder(Player player, FishHook hook) {
        try {
            Class<?> vec3 = Class.forName("net.minecraft.world.phys.Vec3");
            Object motion = vec3.getConstructor(double.class, double.class, double.class).newInstance(0.0, PULL, 0.0);
            Object packet = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket")
                .getConstructor(int.class, vec3).newInstance(hook.getEntityId(), motion);
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object connection = handle.getClass().getField("connection").get(handle);
            connection.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"))
                .invoke(connection, packet);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not pull the bobber under", e);
        }
    }

    private void cancel(UUID id) {
        BukkitTask task = waiting.remove(id);
        if (task != null) {
            task.cancel();
        }
    }
}
