package kr.junhyung.mcagents.render;

public final class FoundItemRenderer implements Renderer<FoundItemRenderer.View> {

    public record View(String query, Stack item) {}

    @Override
    public String render(View view) {
        Stack item = view.item();

        if (item == null) {
            return "No inventory item matches \"" + view.query() + "\".";
        }

        return "Found " + Stack.describe(item) + " x" + item.count() + " in slot " + item.slot() + ".";
    }
}
