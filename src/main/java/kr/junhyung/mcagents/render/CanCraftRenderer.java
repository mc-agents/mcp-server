package kr.junhyung.mcagents.render;

import java.util.ArrayList;
import java.util.List;

public final class CanCraftRenderer implements Renderer<CanCraftRenderer.View> {

    public record View(String item, boolean craftable, boolean hasRecipe, List<Ingredient> missing,
        boolean needsTable, boolean onlyWhatTheBotKnows) {}

    @Override
    public String render(View view) {
        if (view.craftable()) {
            return "Yes, " + view.item() + " can be crafted now.";
        }

        if (!view.hasRecipe()) {
            /*
            A client is sent a recipe as it unlocks it and never the whole set, so "no recipe" from
            one of those is about the bot and not about the game. Saying which is the difference
            between "this item cannot be made" and "teach the bot first".
            */
            return view.onlyWhatTheBotKnows()
                ? "This bot has not been taught a recipe for " + view.item()
                    + ". A Minecraft client only knows the recipes the server has unlocked for it,"
                    + " so this is not the same as there being none."
                : "No recipe produces " + view.item() + ".";
        }

        List<String> reasons = new ArrayList<>(view.missing().stream().map(Ingredient::toString).toList());

        if (view.needsTable()) {
            reasons.add("a crafting table in reach");
        }

        return "No. " + view.item() + " still needs: " + String.join(", ", reasons) + ".";
    }
}
