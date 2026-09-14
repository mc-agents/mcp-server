package kr.junhyung.mcagents.fixture;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * An NPC conversation the way hyperfarm runs one, cut down to what a case can drive: drawn on the
 * action bar, advanced by a jump, answered with a hotbar slot, left by sneaking.
 *
 * <p>The page is sent again every half second, because an action bar fades and a conversation that
 * is still open has to stay on the screen. What was chosen and that the player left are tags, which
 * rcon reads back.
 */
final class Conversation {

    private static final String SPEAKER = "[Probe Farmer] ";
    private static final String[] OPTIONS = {"Wheat", "Carrot"};
    private static final long REDRAW_TICKS = 10;

    private enum Page { GREETING, QUESTION, ANSWERED }

    private final Plugin plugin;
    private final Map<UUID, Page> open = new ConcurrentHashMap<>();
    private final Map<UUID, String> answers = new ConcurrentHashMap<>();
    private BukkitTask redraw;

    Conversation(Plugin plugin) {
        this.plugin = plugin;
    }

    void talk(CommandSender sender, Player player) {
        player.removeScoreboardTag("fixture_talk_left");
        for (int option = 0; option < OPTIONS.length; option++) {
            player.removeScoreboardTag("fixture_choice_" + option);
        }
        open.put(player.getUniqueId(), Page.GREETING);
        draw(player);
        if (redraw == null) {
            redraw = Bukkit.getScheduler().runTaskTimer(plugin, this::redraw, REDRAW_TICKS, REDRAW_TICKS);
        }
        sender.sendMessage("talking to " + player.getName());
    }

    void jumped(Player player) {
        if (open.replace(player.getUniqueId(), Page.GREETING, Page.QUESTION)) {
            draw(player);
        }
    }

    void chose(Player player, int slot) {
        if (slot < OPTIONS.length && open.replace(player.getUniqueId(), Page.QUESTION, Page.ANSWERED)) {
            player.addScoreboardTag("fixture_choice_" + slot);
            answers.put(player.getUniqueId(), OPTIONS[slot]);
            draw(player);
        }
    }

    void sneaked(Player player) {
        if (open.remove(player.getUniqueId()) != null) {
            answers.remove(player.getUniqueId());
            player.addScoreboardTag("fixture_talk_left");
            player.sendActionBar(Component.text(SPEAKER + "See you."));
        }
    }

    private void redraw() {
        for (UUID id : open.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                open.remove(id);
                answers.remove(id);
            } else {
                draw(player);
            }
        }
    }

    private void draw(Player player) {
        Page page = open.get(player.getUniqueId());
        if (page == null) {
            return;
        }
        String line = switch (page) {
            case GREETING -> "Fine day for it. (jump)";
            case QUESTION -> "What will you plant? 0: " + OPTIONS[0] + " / 1: " + OPTIONS[1] + " (sneak to leave)";
            case ANSWERED -> answers.get(player.getUniqueId()) + " it is. (sneak to leave)";
        };
        player.sendActionBar(Component.text(SPEAKER + line));
    }
}
