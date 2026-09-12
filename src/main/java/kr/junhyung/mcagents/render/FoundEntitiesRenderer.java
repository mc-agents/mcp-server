package kr.junhyung.mcagents.render;

import java.util.List;
import tools.jackson.databind.JsonNode;

public final class FoundEntitiesRenderer implements Renderer<FoundEntitiesRenderer.View> {

    public record Entity(String label, String type, Point position, double distance,
        JsonNode labelComponent) {}

    public record View(String query, double maxDistance, List<Entity> entities) {}

    /**
     * The id in brackets after the name, and left out when the name is the id: an unnamed cow reads
     * "cow" and not "cow (cow)". A custom name hides what a thing is, which is the case the bracket
     * exists for.
     */
    private static String line(Entity one) {
        String read = Flatten.read(one.label(), one.labelComponent());
        String named = read.equals(one.type()) ? read : read + " (" + one.type() + ")";

        return "- " + named + " at " + one.position() + ", " + Text.oneDecimal(one.distance()) + " blocks away";
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
