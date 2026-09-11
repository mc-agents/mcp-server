package dev.mcagents.mcp.render;

import java.util.List;

public final class BossBarsRenderer implements Renderer<BossBarsRenderer.View> {

    public record Bar(String title, double progress, String color, int dividers) {}

    public record View(List<Bar> bars) {}

    @Override
    public String render(View view) {
        if (view.bars().isEmpty()) {
            return "No boss bars are showing.";
        }

        return String.join("\n", view.bars().stream().map(BossBarsRenderer::bar).toList());
    }

    private static String bar(Bar bar) {
        String title = bar.title().isEmpty() ? "(untitled)" : bar.title();

        return "boss bar \"" + title + "\" (" + Text.percent(bar.progress()) + "%, "
            + bar.color() + ", " + bar.dividers() + " segments)";
    }
}
