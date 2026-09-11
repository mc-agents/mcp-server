package dev.mcagents.mcp.render;

import java.util.List;
import java.util.stream.IntStream;

public final class FoundBlocksRenderer implements Renderer<FoundBlocksRenderer.View> {

    public record View(String blockType, double maxDistance, List<Point> positions) {}

    @Override
    public String render(View view) {
        List<Point> positions = view.positions();
        String distance = Text.number(view.maxDistance());

        if (positions.isEmpty()) {
            return "No " + view.blockType() + " within " + distance + " blocks.";
        }

        List<String> lines = IntStream.range(0, positions.size())
            .mapToObj(index -> (index + 1) + ". " + positions.get(index))
            .toList();

        return Text.withLines(
            "Found " + positions.size() + " " + view.blockType() + " within " + distance + " blocks:",
            lines);
    }
}
