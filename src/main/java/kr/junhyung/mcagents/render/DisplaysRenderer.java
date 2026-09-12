package kr.junhyung.mcagents.render;

import java.util.List;

public final class DisplaysRenderer implements Renderer<DisplaysRenderer.View> {

    public record Display(String text, String entity, Point position, double distance,
        List<Piece> segments, int glyphPieces) {}

    public record View(double maxDistance, List<Display> displays) {}

    @Override
    public String render(View view) {
        if (view.displays().isEmpty()) {
            return "No text is being displayed within " + Text.number(view.maxDistance()) + " blocks.";
        }

        List<String> lines = view.displays().stream().map(DisplaysRenderer::display).toList();

        return Text.withLines(view.displays().size() + " displayed " + Text.DATA_NOTICE + ":", lines);
    }

    /**
     * A display drawn only from glyphs is an icon: something is there and there is nothing to read.
     * Reporting it as a blank line said "there is a display here and it is empty", which is a
     * different and wrong thing -- a real server's nameplates came back as three empty lines.
     */
    private static String display(Display one) {
        String said = Piece.join(one.segments(), one.text());

        if (said.isEmpty()) {
            said = one.glyphPieces() > 0
                ? "(" + one.glyphPieces() + " glyph piece(s), nothing to read)"
                : "(empty)";
        }

        return "- " + said + " (" + one.entity() + " at " + one.position().x() + ", "
            + one.position().y() + ", " + one.position().z() + ", "
            + Text.oneDecimal(one.distance()) + " blocks away)";
    }
}
