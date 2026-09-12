package kr.junhyung.mcagents.render;

import java.util.List;

/**
 * What a click did to the slot it landed on.
 *
 * <p>The slot before and after, and the cursor, because the question a QA run asks next is whether
 * the click moved anything: a plugin that cancels the click leaves both unchanged, and a sentence
 * saying only which slot was clicked cannot tell that from a click that worked. Without this the
 * caller had to follow every click with read-window to find out.
 */
public final class ClickedSlotRenderer implements Renderer<ClickedSlotRenderer.View> {

    public record View(int slot, String button, boolean shift, Held before, Held after, Held cursor) {}

    @Override
    public String render(View view) {
        String header = capitalised((view.shift() ? "shift-" : "") + view.button()) + "-clicked slot "
            + view.slot() + ".";

        return Text.withLines(header, List.of(
            "  slot " + view.slot() + ": " + Held.describe(view.before()) + " -> " + Held.describe(view.after()),
            "  cursor: " + Held.describe(view.cursor())));
    }

    private static String capitalised(String opening) {
        return Character.toUpperCase(opening.charAt(0)) + opening.substring(1);
    }
}
