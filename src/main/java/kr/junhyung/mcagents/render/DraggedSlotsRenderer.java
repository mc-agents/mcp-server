package kr.junhyung.mcagents.render;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * What a drag left in each slot it was asked to cross.
 *
 * <p>Every slot is listed, including the ones that came out unchanged. A drag passes over a slot
 * that holds something else or that the cursor has run out for, and a plugin can cancel the whole
 * drag; listing only the slots that changed would make both of those look like a shorter request.
 * The cursor goes before and after, because what was on it is the thing that was spread.
 */
public final class DraggedSlotsRenderer implements Renderer<DraggedSlotsRenderer.View> {

    /** {@code window} is null from a bot built before it, and when the drag was answered in the window dragged. */
    public record View(String button, List<Entry> slots, Held carried, Held cursor, ClickedSlotRenderer.Replaced window) {}

    public record Entry(int slot, Held before, Held after) {}

    @Override
    public String render(View view) {
        String across = view.slots().stream()
            .map(entry -> String.valueOf(entry.slot()))
            .collect(Collectors.joining(", "));
        String header = Character.toUpperCase(view.button().charAt(0)) + view.button().substring(1)
            + "-dragged across slots " + across + ".";

        List<String> lines = new ArrayList<>();
        if (view.window() != null) {
            lines.add("  " + view.window().describe());
            lines.add("  cursor: " + Held.describe(view.carried()) + " -> " + Held.describe(view.cursor()));
            return Text.withLines(header, lines);
        }
        for (Entry entry : view.slots()) {
            lines.add("  slot " + entry.slot() + ": " + Held.describe(entry.before()) + " -> "
                + Held.describe(entry.after()));
        }
        lines.add("  cursor: " + Held.describe(view.carried()) + " -> " + Held.describe(view.cursor()));

        return Text.withLines(header, lines);
    }
}
