package kr.junhyung.mcagents.render;

import java.util.List;
import tools.jackson.databind.JsonNode;

public final class BossBarsRenderer implements Renderer<BossBarsRenderer.View> {

    public record Bar(String title, double progress, String color, int dividers, List<Piece> segments,
        JsonNode component) {}

    public record View(List<Bar> bars) {}

    @Override
    public String render(View view) {
        if (view.bars().isEmpty()) {
            return "No boss bars are showing.";
        }

        return String.join("\n", view.bars().stream().map(BossBarsRenderer::bar).toList());
    }

    /**
     * The title comes from its pieces when it has them. A server builds a boss bar out of stacked
     * labels the way it builds an action bar, and one real one read "\uc5b4\ub518\uac00\uacb0\uc6b8 6\uc77c" where the screen
     * showed a place, a date and a channel side by side.
     *
     * <p>"notches" and not "segments": the divisions Minecraft draws across the bar are a different
     * thing from the pieces its title is written in, and calling both segments in one line asks the
     * reader to work out which.
     */
    private static String bar(Bar bar) {
        List<Piece> pieces = Flatten.pieces(bar.component());
        String title = Piece.join(pieces == null ? bar.segments() : pieces, bar.title());

        return "boss bar \"" + (title.isEmpty() ? "(untitled)" : title) + "\" ("
            + Text.percent(bar.progress()) + "%, " + bar.color() + ", " + bar.dividers() + " notches)";
    }
}
