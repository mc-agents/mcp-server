package kr.junhyung.mcagents.fixture;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * hyperfarm's gathering timing, cut down to one round: a cue on the action bar at a moment nobody can
 * predict, and a left-click within two ticks of it.
 *
 * <p>Two ticks is less than an MCP round trip, which is the point: only a press made inside the bot
 * on the tick the cue arrives can land in it. How many ticks the click took is kept as well as whether
 * it hit, so a case can report what a kind of bot actually manages rather than only pass or fail.
 */
final class Gathering implements Listener {

    private static final String CUE = "Gather: JUST!";
    private static final int WINDOW_TICKS = 2;
    /* The cue comes this long after the command, so a press started right after it is already listening. */
    private static final int EARLIEST_CUE_TICKS = 30;
    private static final int LATEST_CUE_TICKS = 70;
    /* A round nobody clicked in is a miss once the cue is this old. */
    private static final int GIVE_UP_TICKS = 40;

    private record Round(BukkitTask pending, int cuedAt, long cuedNanos) {}

    private final Plugin plugin;
    private final FixturePlugin.Scores scores;
    private final Map<UUID, Round> rounds = new ConcurrentHashMap<>();

    Gathering(Plugin plugin, FixturePlugin.Scores scores) {
        this.plugin = plugin;
        this.scores = scores;
    }

    void start(CommandSender sender, Player player) {
        end(player.getUniqueId());
        player.sendActionBar(Component.text("Gather: steady..."));

        long delay = ThreadLocalRandom.current().nextInt(EARLIEST_CUE_TICKS, LATEST_CUE_TICKS + 1);
        BukkitTask cue = Bukkit.getScheduler().runTaskLater(plugin, () -> cue(player), delay);
        rounds.put(player.getUniqueId(), new Round(cue, -1, 0));
        sender.sendMessage("gathering with " + player.getName());
    }

    private void cue(Player player) {
        UUID id = player.getUniqueId();
        if (!player.isOnline()) {
            rounds.remove(id);
            return;
        }
        int now = Bukkit.getCurrentTick();
        player.sendActionBar(Component.text(CUE));
        BukkitTask giveUp = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (rounds.remove(id) != null) {
                scores.add("fx_gather_miss", player.getName());
                scores.set("fx_gather_ticks", player.getName(), -1);
            }
        }, GIVE_UP_TICKS);
        rounds.put(id, new Round(giveUp, now, System.nanoTime()));
    }

    /** Air or block, main hand: a gathering swing is a swing at whatever is in front of the player. */
    @EventHandler(priority = EventPriority.MONITOR)
    void click(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND
                || event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        Round round = rounds.get(player.getUniqueId());
        if (round == null) {
            return;
        }
        end(player.getUniqueId());

        /* A click before the cue is a guess, and the round is lost to it. */
        if (round.cuedAt() < 0) {
            scores.add("fx_gather_early", player.getName());
            scores.add("fx_gather_miss", player.getName());
            return;
        }
        int took = Bukkit.getCurrentTick() - round.cuedAt();
        scores.set("fx_gather_ticks", player.getName(), took);
        /* Ticks are coarse where the answer is usually "the same one"; the milliseconds say how close. */
        scores.set("fx_gather_ms", player.getName(), (int) ((System.nanoTime() - round.cuedNanos()) / 1_000_000L));
        scores.add(took <= WINDOW_TICKS ? "fx_gather_hit" : "fx_gather_miss", player.getName());
    }

    private void end(UUID id) {
        Round round = rounds.remove(id);
        if (round != null) {
            round.pending().cancel();
        }
    }
}
