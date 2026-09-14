package kr.junhyung.mcagents.render;

import java.util.List;

public final class FoundItemRenderer implements Renderer<FoundItemRenderer.View> {

    public record View(String query, Stack item) {}

    @Override
    public String render(View view) {
        Stack item = view.item();

        if (item == null) {
            return "No inventory item matches \"" + view.query() + "\".";
        }

        List<String> notes = Stack.notes(item);
        String noted = notes.isEmpty() ? "" : " (" + String.join(", ", notes) + ")";

        return "Found " + Stack.describe(item) + " x" + item.count() + " in slot " + item.slot() + noted + "."
            + Stack.lore(item);
    }
}
