package kr.junhyung.mcagents.render;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The tooltip a slot shows when the cursor rests on it, and whether the frame beside the text has
 * it drawn.
 *
 * <p>The lines are the ones the client builds for the stack -- display name, enchantments,
 * attributes, whatever the item adds -- read the way read-window reads a label, so a lore line
 * drawn in the pack's own font carries its font label. The image itself never reaches a renderer;
 * {@code frame} is what says one is attached, and the sentence names its size the way a screenshot
 * does.
 *
 * <p>A tooltip the client would build but does not draw is still reported, as what the lines
 * "would read": the words are what a QA run is usually after, and why the frame shows none is the
 * other half of the answer.
 */
public final class HoveredSlotRenderer implements Renderer<HoveredSlotRenderer.View> {

    public record Tooltip(List<String> lines, List<JsonNode> lineComponents) {}

    public record Frame(int width, int height) {}

    /** {@code hidden} is null when the tooltip is drawn, else why it is not: empty, inactive or cursor. */
    public record View(int slot, Held item, Held cursor, Tooltip tooltip, String hidden, Frame frame) {}

    @Override
    public String render(View view) {
        if ("empty".equals(view.hidden())) {
            return "Hovered slot " + view.slot() + ", which is empty: no tooltip is drawn."
                + (view.frame() == null ? "" : " The " + size(view.frame()) + " frame is attached.");
        }

        String header = switch (view.hidden()) {
            case null -> "Hovered slot " + view.slot() + " (" + Held.describe(view.item()) + "). Its tooltip reads:";
            case "inactive" -> "Slot " + view.slot() + " (" + Held.describe(view.item())
                + ") did not take the hover: the screen has it inactive or covered (another creative tab, the recipe book), so no tooltip is drawn. Its lines would read:";
            case "cursor" -> "Hovered slot " + view.slot() + " (" + Held.describe(view.item()) + "), but the cursor holds "
                + Held.describe(view.cursor()) + " and the tooltip stays hidden while it does. Its lines would read:";
            default -> throw new IllegalArgumentException("unknown hidden " + view.hidden());
        };

        List<String> lines = new ArrayList<>();
        for (String line : lines(view.tooltip())) {
            lines.add("  " + line);
        }
        if (view.frame() != null) {
            lines.add(view.hidden() == null
                ? "The " + size(view.frame()) + " frame attached shows it drawn."
                : "The " + size(view.frame()) + " frame is attached.");
        }
        return lines.isEmpty() ? header : Text.withLines(header, lines);
    }

    private static List<String> lines(Tooltip tooltip) {
        return tooltip == null ? List.of() : Flatten.readAll(tooltip.lines(), tooltip.lineComponents());
    }

    private static String size(Frame frame) {
        return frame.width() + "x" + frame.height();
    }
}
