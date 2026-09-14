package kr.junhyung.mcagents.render;

import java.util.ArrayList;
import java.util.List;

public final class InventoryRenderer implements Renderer<InventoryRenderer.View> {

    public record View(List<Stack> items) {}

    @Override
    public String render(View view) {
        if (view.items().isEmpty()) {
            return "Inventory is empty.";
        }

        List<String> lines = view.items().stream().map(InventoryRenderer::line).toList();

        return Text.withLines(view.items().size() + " item stack(s):", lines);
    }

    private static String line(Stack item) {
        List<String> where = new ArrayList<>(List.of("slot " + item.slot()));
        where.addAll(Stack.notes(item));

        return "- " + Stack.describe(item) + " x" + item.count() + " (" + String.join(", ", where) + ")"
            + Stack.lore(item);
    }
}
