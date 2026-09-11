package kr.junhyung.mcagents.render;

import java.util.List;

public final class FoundEntitiesRenderer implements Renderer<FoundEntitiesRenderer.View> {

    public record Entity(String label, String type, Point position, double distance) {}

    public record View(String query, double maxDistance, List<Entity> entities) {}

    @Override
    public String render(View view) {
        if (view.entities().isEmpty()) {
            String what = view.query() == null ? "entity" : view.query();

            return "No " + what + " within " + Text.number(view.maxDistance()) + " blocks.";
        }

        List<String> lines = view.entities().stream()
            .map(one -> "- " + one.label() + " (" + one.type() + ") at " + one.position()
                + ", " + Text.oneDecimal(one.distance()) + " blocks away")
            .toList();

        return Text.withLines("Found " + view.entities().size() + " entity/entities:", lines);
    }
}
