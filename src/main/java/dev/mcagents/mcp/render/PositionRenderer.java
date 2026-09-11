package dev.mcagents.mcp.render;

public final class PositionRenderer implements Renderer<PositionRenderer.View> {

    public record View(Point position) {}

    @Override
    public String render(View view) {
        return "Position: " + view.position();
    }
}
