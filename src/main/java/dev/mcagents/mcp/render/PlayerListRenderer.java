package dev.mcagents.mcp.render;

import java.util.List;

public final class PlayerListRenderer implements Renderer<PlayerListRenderer.View> {

    public record Player(String name, String gameMode, Integer ping, boolean self) {}

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

    private static String player(Player player) {
        String ping = player.ping() == null ? "unknown" : player.ping() + "ms";
        String marker = player.self() ? " (this bot)" : "";

        return "  " + player.name() + marker + ": " + player.gameMode() + ", " + ping;
    }
}
