package kr.junhyung.mcagents.fixture;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

/**
 * The half of the fixture a datapack cannot be: what the server received from a player's keys.
 *
 * <p>A client can show a jump, a slot or a click that never left it, so a case about input asks the
 * server. Everything is kept in scoreboard objectives, which rcon reads without anything of its own.
 */
public final class FixturePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        Scores scores = new Scores(Bukkit.getScoreboardManager().getMainScoreboard());
        Conversation conversation = new Conversation(this);
        Gathering gathering = new Gathering(this, scores);
        LockedMenu locked = new LockedMenu();

        getServer().getPluginManager().registerEvents(new InputRecorder(scores, conversation), this);
        getServer().getPluginManager().registerEvents(new FishingBite(this, scores), this);
        getServer().getPluginManager().registerEvents(gathering, this);
        getServer().getPluginManager().registerEvents(locked, this);

        PluginCommand command = getCommand("fixture");
        if (command != null) {
            command.setExecutor((sender, ignored, label, args) -> {
                if (args.length != 2) {
                    return false;
                }
                Player player = Bukkit.getPlayerExact(args[1]);
                if (player == null) {
                    sender.sendMessage(args[1] + " is not online");
                    return true;
                }
                switch (args[0]) {
                    case "talk" -> conversation.talk(sender, player);
                    case "gather" -> gathering.start(sender, player);
                    case "locked" -> locked.open(sender, player);
                    default -> {
                        return false;
                    }
                }
                return true;
            });
        }
    }

    /** A counter per player, named by what it counts. */
    static final class Scores {

        private final Scoreboard board;

        Scores(Scoreboard board) {
            this.board = board;
        }

        void add(String objective, String player) {
            var score = objective(objective).getScore(player);
            score.setScore(score.getScore() + 1);
        }

        void set(String objective, String player, int value) {
            objective(objective).getScore(player).setScore(value);
        }

        private Objective objective(String name) {
            Objective objective = board.getObjective(name);
            return objective != null ? objective : board.registerNewObjective(name, Criteria.DUMMY, Component.text(name));
        }
    }
}
