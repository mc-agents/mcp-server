package kr.junhyung.mcagents.render;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

public final class FoundEntitiesRenderer implements Renderer<FoundEntitiesRenderer.View> {

    public record Entity(String label, String type, Point position, double distance,
        JsonNode labelComponent, Shown item, Block block) {}

    /** The item an item_display holds up. */
    public record Shown(String name, int count, String label, JsonNode labelComponent, String itemModel) {}

    /** The block state a block_display draws. */
    public record Block(String name, Map<String, String> properties) {}

    public record View(String query, double maxDistance, List<Entity> entities) {}

    /**
     * The id in brackets after the name, and left out when the name is the id: an unnamed cow reads
     * "cow" and not "cow (cow)". A custom name hides what a thing is, which is the case the bracket
     * exists for.
     */
    private static String line(Entity one) {
        String read = Flatten.read(one.label(), one.labelComponent());
        String named = read.equals(one.type()) ? read : read + " (" + one.type() + ")";

        return "- " + named + " at " + one.position() + ", " + Text.oneDecimal(one.distance()) + " blocks away"
            + showing(one);
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

            return "No " + what + " within " + Text.number(view.maxDistance()) + " blocks.";
        }

        List<String> lines = view.entities().stream().map(FoundEntitiesRenderer::line).toList();

        return Text.withLines("Found " + view.entities().size() + " entity/entities:", lines);
    }
}
