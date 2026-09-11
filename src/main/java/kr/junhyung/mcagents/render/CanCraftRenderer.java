package kr.junhyung.mcagents.render;

import java.util.ArrayList;
import java.util.List;

public final class CanCraftRenderer implements Renderer<CanCraftRenderer.View> {

    public record View(String item, boolean craftable, boolean hasRecipe, List<Ingredient> missing, boolean needsTable) {}

    @Override
    public String render(View view) {
        if (view.craftable()) {
            return "Yes, " + view.item() + " can be crafted now.";
        }

        if (!view.hasRecipe()) {
            return "No recipe produces " + view.item() + ".";
        }

        List<String> reasons = new ArrayList<>(view.missing().stream().map(Ingredient::toString).toList());

        if (view.needsTable()) {
            reasons.add("a crafting table in reach");
        }

        return "No. " + view.item() + " still needs: " + String.join(", ", reasons) + ".";
    }
}
