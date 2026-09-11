package kr.junhyung.mcagents.render;

import java.util.List;

public final class RecipeListRenderer implements Renderer<RecipeListRenderer.View> {

    public record Recipe(Ingredient result, List<Ingredient> ingredients, List<Ingredient> missing,
        boolean requiresTable) {}

    /**
     * {@code item} names the one item that was asked about; without it this is the scan of everything the
     * inventory reaches, which is the only mode that can run out of room.
     */
    public record View(String item, boolean tableInReach, Integer stoppedAt, List<Recipe> recipes) {}

    @Override
    public String render(View view) {
        List<String> lines = view.recipes().stream().map(RecipeListRenderer::recipe).toList();

        if (view.item() != null) {
            if (view.recipes().isEmpty()) {
                return "No recipe produces " + view.item() + ".";
            }

            return Text.withLines("Recipes for " + view.item() + ":", lines);
        }

        if (view.recipes().isEmpty()) {
            return "Nothing in the inventory is enough for any recipe.";
        }

        String table = view.tableInReach() ? " (a crafting table is in reach)" : " (no crafting table in reach)";
        String suffix = view.stoppedAt() == null ? "" : "\n(stopped at " + view.stoppedAt() + " entries)";

        return Text.withLines("Craftable right now" + table + ":", lines) + suffix;
    }

    private static String recipe(Recipe recipe) {
        String ingredients = String.join(", ", recipe.ingredients().stream().map(Ingredient::toString).toList());
        String status = recipe.missing().isEmpty()
            ? "craftable"
            : "missing " + String.join(", ", recipe.missing().stream().map(Ingredient::toString).toList());
        String table = recipe.requiresTable() ? ", needs a crafting table" : "";

        return "- " + recipe.result().name() + " x" + recipe.result().count() + " <- " + ingredients
            + " [" + status + table + "]";
    }
}
