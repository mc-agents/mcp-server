package kr.junhyung.mcagents.render;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * What a click did to the slot it landed on.
 *
 * <p>The slot before and after, and the cursor, because the question a QA run asks next is whether
 * the click moved anything: a plugin that cancels the click leaves both unchanged, and a sentence
 * saying only which slot was clicked cannot tell that from a click that worked. Without this the
 * caller had to follow every click with read-window to find out.
 *
 * <p>The header says what was pressed and never what it achieved, for the same reason. A swap adds
 * the line for the stack on the other side, which is the half a cancelled swap is noticed by when
 * the slot in the window was empty to begin with.
 *
 * <p>A plugin's menu often answers a click with another window, or closes the one clicked. The slot
 * lines are left out then: the window they belonged to is gone, and what a bot last guessed it held
 * read as the click having done nothing, which is how a +1 in a shop looked cancelled.
 */
public final class ClickedSlotRenderer implements Renderer<ClickedSlotRenderer.View> {

    /**
     * {@code outside} is boxed because a bot built before it existed sends none, and means false then.
     * {@code window} is null the same way, and when the click was answered in the window clicked.
     */
    public record View(Integer slot, Boolean outside, String button, boolean shift, String mode, Integer hotbar,
        Held before, Held after, Held cursor, Swapped swapped, Replaced window) {}

    public record Swapped(Held before, Held after) {}

    /** The window the server answered with instead of the one clicked. */
    public record Replaced(boolean closed, String title, JsonNode titleComponent) {

        String describe() {
            return closed ? "The server closed the window."
                : "The server opened window \"" + Flatten.read(title, titleComponent) + "\" instead; read-window shows it.";
        }
    }

    /**
     * A click outside the window lands on no slot, so the one line is the cursor's: what it held and
     * what the drop left on it.
     */
    @Override
    public String render(View view) {
        if (view.window() != null) {
            return Text.withLines(Boolean.TRUE.equals(view.outside()) ? capitalised(view.button()) + "-clicked outside the window." : header(view),
                List.of("  " + view.window().describe(), "  cursor: " + Held.describe(view.cursor())));
        }
        if (Boolean.TRUE.equals(view.outside())) {
            return capitalised(view.button()) + "-clicked outside the window.\n  cursor: "
                + Held.describe(view.before()) + " -> " + Held.describe(view.after());
        }

        List<String> lines = new ArrayList<>();
        lines.add("  slot " + view.slot() + ": " + Held.describe(view.before()) + " -> " + Held.describe(view.after()));

        if (view.swapped() != null) {
            String other = view.hotbar() == null ? "offhand" : "hotbar " + view.hotbar();
            lines.add("  " + other + ": " + Held.describe(view.swapped().before()) + " -> "
                + Held.describe(view.swapped().after()));
        }
        lines.add("  cursor: " + Held.describe(view.cursor()));

        return Text.withLines(header(view), lines);
    }

    private static String header(View view) {
        return switch (view.mode()) {
            case "click" -> capitalised((view.shift() ? "shift-" : "") + view.button()) + "-clicked slot "
                + view.slot() + ".";
            case "swap-hotbar" -> "Pressed hotbar key " + view.hotbar() + " over slot " + view.slot() + ".";
            case "swap-offhand" -> "Pressed the offhand key over slot " + view.slot() + ".";
            case "throw-one" -> "Pressed drop over slot " + view.slot() + ".";
            case "throw-stack" -> "Pressed drop with control over slot " + view.slot() + ".";
            case "pickup-all" -> "Double-clicked slot " + view.slot() + ".";
            case "clone" -> "Middle-clicked slot " + view.slot() + ".";
            default -> throw new IllegalArgumentException("unknown mode " + view.mode());
        };
    }

    private static String capitalised(String opening) {
        return Character.toUpperCase(opening.charAt(0)) + opening.substring(1);
    }
}
