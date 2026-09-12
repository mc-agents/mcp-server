package kr.junhyung.mcagents.render;

import java.util.List;
import tools.jackson.databind.JsonNode;

public final class PlayerListRenderer implements Renderer<PlayerListRenderer.View> {

    /**
     * {@code name} is the username, which is the identity every other tool takes;
     * {@code displayName} is what the tab list draws, which is where a server puts a rank.
     */
    public record Player(String name, String gameMode, Integer ping, boolean self,
        String displayName, JsonNode displayNameComponent) {}

    public record View(List<Player> players) {}

    @Override
    public String render(View view) {
        if (view.players().isEmpty()) {
            return "The tab list is empty.";
        }

        return Text.withLines(
            view.players().size() + " players online",
            view.players().stream().map(PlayerListRenderer::player).toList());
    }

    /**
     * The drawn name with the username after it, and the username alone when the server set none or
     * set it to the username. A rank hides who a player is, which is the case the bracket is for --
     * the same shape find-entity uses for a custom name over an entity's id.
     */
    private static String named(Player player) {
        if (player.displayName() == null) {
            return player.name();
        }

        String drawn = Flatten.read(player.displayName(), player.displayNameComponent());

        return drawn.equals(player.name()) ? player.name() : drawn + " (" + player.name() + ")";
    }

    private static String player(Player player) {
        String ping = player.ping() == null ? "unknown" : player.ping() + "ms";
        String marker = player.self() ? " (this bot)" : "";

        return "  " + named(player) + marker + ": " + player.gameMode() + ", " + ping;
    }
}
