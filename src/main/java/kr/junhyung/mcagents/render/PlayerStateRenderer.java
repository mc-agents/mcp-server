package kr.junhyung.mcagents.render;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class PlayerStateRenderer implements Renderer<PlayerStateRenderer.View> {

    private static final double FULL_HEALTH = 20;

    public record Experience(int level, double progress, int points) {}

    public record Ridden(String type, int id) {

        @Override
        public String toString() {
            return type + " (id " + id + ")";
        }
    }

    public record Vehicle(String type, int id, Ridden seat) {}

    public record View(double health, double food, double saturation, Experience experience, String gameMode,
        String dimension, Point position, Double oxygen, Boolean dead, String causeOfDeath,
        JsonNode causeOfDeathComponent, Vehicle vehicle) {}

    /**
     * A dead bot's health reads 0 / 20 and nothing else about it looks wrong, so the first line
     * says it outright. Every tool that acts in the world refuses until respawn, and a caller
     * reading this is usually one that has just been refused.
     *
     * <p>{@code dead} is boxed because a bot built before it existed sends none, and that bot's
     * state still has to render rather than fail on a field it never knew about.
     */
    @Override
    public String render(View view) {
        Experience experience = view.experience();
        List<String> lines = new ArrayList<>();

        if (Boolean.TRUE.equals(view.dead())) {
            String cause = view.causeOfDeath() == null
                ? ""
                : " (" + Flatten.read(view.causeOfDeath(), view.causeOfDeathComponent()) + ")";

            lines.add("dead" + cause + ": call respawn to bring the bot back");
        }

        lines.addAll(List.of(
            "health: " + Text.number(view.health()) + " / 20",
            "food: " + Text.number(view.food()) + " / 20 (saturation " + Text.number(view.saturation()) + ")",
            "health bar: " + Text.percent(view.health() / FULL_HEALTH) + "%",
            "experience: level " + experience.level() + ", " + Text.percent(experience.progress())
                + "% to the next level, " + experience.points() + " points",
            "gameMode: " + view.gameMode() + " / dimension: " + view.dimension(),
            "position: " + (view.position() == null ? "unknown" : view.position().toString()),
            "oxygen: " + (view.oxygen() == null ? "full" : Text.number(view.oxygen())) + " / 20"));

        /* Only while riding, so a bot on its feet reads exactly as it did before vehicles were reported. */
        if (view.vehicle() != null) {
            lines.add("riding: " + riding(view.vehicle()));
        }

        return String.join("\n", lines);
    }

    private static String riding(Vehicle vehicle) {
        String ridden = new Ridden(vehicle.type(), vehicle.id()).toString();
        return vehicle.seat() == null ? ridden : ridden + ", seated on " + vehicle.seat();
    }
}
