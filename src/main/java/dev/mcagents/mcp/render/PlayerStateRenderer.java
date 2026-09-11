package dev.mcagents.mcp.render;

import java.util.List;

public final class PlayerStateRenderer implements Renderer<PlayerStateRenderer.View> {

    private static final double FULL_HEALTH = 20;

    public record Experience(int level, double progress, int points) {}

    public record View(double health, double food, double saturation, Experience experience, String gameMode,
        String dimension, Point position, Double oxygen) {}

    @Override
    public String render(View view) {
        Experience experience = view.experience();

        return String.join("\n", List.of(
            "health: " + Text.number(view.health()) + " / 20",
            "food: " + Text.number(view.food()) + " / 20 (saturation " + Text.number(view.saturation()) + ")",
            "health bar: " + Text.percent(view.health() / FULL_HEALTH) + "%",
            "experience: level " + experience.level() + ", " + Text.percent(experience.progress())
                + "% to the next level, " + experience.points() + " points",
            "gameMode: " + view.gameMode() + " / dimension: " + view.dimension(),
            "position: " + (view.position() == null ? "unknown" : view.position().toString()),
            "oxygen: " + (view.oxygen() == null ? "full" : Text.number(view.oxygen())) + " / 20"));
    }
}
