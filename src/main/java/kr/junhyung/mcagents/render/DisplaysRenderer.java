package kr.junhyung.mcagents.render;

import java.util.List;

public final class DisplaysRenderer implements Renderer<DisplaysRenderer.View> {

    public record Display(String text, String entity, Point position, double distance) {}

    public record View(double maxDistance, List<Display> displays) {}

    @Override
    public String render(View view) {
        if (view.displays().isEmpty()) {
            return "No text is being displayed within " + Text.number(view.maxDistance()) + " blocks.";
        }

        List<String> lines = view.displays().stream()
            .map(one -> "- " + one.text() + " (" + one.entity() + " at " + one.position().x() + ", "
                + one.position().y() + ", " + one.position().z() + ", "
                + Text.oneDecimal(one.distance()) + " blocks away)")
            .toList();

        return Text.withLines(view.displays().size() + " displayed " + Text.DATA_NOTICE + ":", lines);
    }
}
