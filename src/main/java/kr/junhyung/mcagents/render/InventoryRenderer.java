package kr.junhyung.mcagents.render;

import java.util.List;

public final class InventoryRenderer implements Renderer<InventoryRenderer.View> {

    public record View(List<Stack> items) {}

    @Override
    public String render(View view) {
        if (view.items().isEmpty()) {
            return "Inventory is empty.";
        }

        List<String> lines = view.items().stream()
            .map(item -> "- " + item.name() + " x" + item.count() + " (slot " + item.slot() + ")")
            .toList();

        return Text.withLines(view.items().size() + " item stack(s):", lines);
    }
}
