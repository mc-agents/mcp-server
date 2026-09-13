package kr.junhyung.mcagents.render;

import tools.jackson.databind.JsonNode;

/**
 * What the bot is looking at.
 *
 * <p>The one thing a screenshot cannot carry is a coordinate. An agent placing a sign, an NPC or a
 * quest marker has to say where, and reading that off a picture is guesswork; pointing at it and
 * asking is how a person does it, and it costs a sentence rather than an image.
 */
public final class TargetRenderer implements Renderer<TargetRenderer.View> {

    /**
     * @param hit      {@code block}, {@code entity}, or {@code nothing} when the reach runs out
     * @param face     the side of the block the bot is looking at, which is where a placed block goes
     * @param label    an entity's name, with its component beside it for the server to flatten
     */
    public record View(String hit, Point position, String block, String face, String label,
        String type, double distance, JsonNode labelComponent) {}

    @Override
    public String render(View view) {
        return switch (view.hit()) {
            case "block" -> "Looking at " + view.block() + " at " + view.position() + ", its "
                + view.face() + " face, " + Text.oneDecimal(view.distance()) + " blocks away.";
            case "entity" -> "Looking at " + named(view) + " at " + view.position() + ", "
                + Text.oneDecimal(view.distance()) + " blocks away.";
            default -> "The bot is not looking at anything within reach.";
        };
    }

    /** The same shape find-entity uses: the name, and the id after it when the name hides it. */
    private static String named(View view) {
        String read = Flatten.read(view.label(), view.labelComponent());

        return read.equals(view.type()) ? read : read + " (" + view.type() + ")";
    }
}
