package kr.junhyung.mcagents.render;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

public final class FoundEntitiesRenderer implements Renderer<FoundEntitiesRenderer.View> {

    /**
     * @param id        null from a bot older than the selectors that take it
     * @param nameplate the label floating over it, which is what a player reads on an NPC with no
     *                  name of its own
     */
    public record Entity(String label, String type, Point position, double distance,
        JsonNode labelComponent, Shown item, Block block, Integer id, String nameplate,
        JsonNode nameplateComponent) {}

    /** The item an item_display holds up. */
    public record Shown(String name, int count, String label, JsonNode labelComponent, String itemModel) {}

    /** The block state a block_display draws. */
    public record Block(String name, Map<String, String> properties) {}

    public record View(String query, double maxDistance, List<Entity> entities) {}

    /**
     * The id in brackets after the name, and left out when the name is the id: an unnamed cow reads
     * "cow" and not "cow (cow)". A custom name hides what a thing is, which is the case the bracket
     * exists for.
     *
     * <p>The label and the entity id come after, because they are what interact-entity is given
     * next: a mannequin with no name reads "mannequin" and nothing else, and the name a player
     * knows it by is the text floating over it.
     */
    private static String line(Entity one) {
        String read = Flatten.read(one.label(), one.labelComponent());
        String named = read.equals(one.type()) ? read : read + " (" + one.type() + ")";
        String labelled = one.nameplate() == null
            ? ""
            : " labelled \"" + Flatten.read(one.nameplate(), one.nameplateComponent()) + "\"";
        String id = one.id() == null ? "" : ", id " + one.id();

        return "- " + named + labelled + " at " + one.position() + ", " + Text.oneDecimal(one.distance())
            + " blocks away" + id + showing(one);
    }

    /**
     * A display is an empty name in the list without this: every item_display reads "item_display",
     * and what tells a chair from a sign is the item it holds and the model that item is drawn with.
     */
    private static String showing(Entity one) {
        if (one.item() != null) {
            Shown item = one.item();
            String named = item.label() == null
                ? item.name()
                : Flatten.read(item.label(), item.labelComponent()) + " [" + item.name() + "]";
            String model = Stack.model(item.name(), item.itemModel());

            return ", showing " + named + " x" + item.count() + (model == null ? "" : " (model " + model + ")");
        }
        if (one.block() != null) {
            Map<String, String> properties = one.block().properties() == null ? Map.of() : one.block().properties();
            String state = properties.isEmpty() ? "" : new TreeMap<>(properties).entrySet().stream()
                .map(property -> property.getKey() + "=" + property.getValue())
                .collect(Collectors.joining(",", "[", "]"));

            return ", showing " + one.block().name() + state;
        }
        return "";
    }

    @Override
    public String render(View view) {
        if (view.entities().isEmpty()) {
            String what = view.query() == null ? "entity" : view.query();

            /* A box was searched, not a radius, and a bot says so by sending no distance to report. */
            return view.maxDistance() <= 0
                    ? "No " + what + " in the box."
                    : "No " + what + " within " + Text.number(view.maxDistance()) + " blocks.";
        }

        List<String> lines = view.entities().stream().map(FoundEntitiesRenderer::line).toList();

        return Text.withLines("Found " + view.entities().size() + " entity/entities:", lines);
    }
}
