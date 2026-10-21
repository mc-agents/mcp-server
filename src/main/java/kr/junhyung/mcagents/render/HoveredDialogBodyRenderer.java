package kr.junhyung.mcagents.render;

import java.util.ArrayList;
import java.util.List;

/**
 * The tooltip a piece of a dialog's body shows, and whether the frame beside the text has it drawn.
 *
 * <p>Read the way hover-slot reads a slot's, except that a glyph is named rather than dropped: a
 * tooltip that prices something in the pack's coin icons says which icon by its code point.
 */
public final class HoveredDialogBodyRenderer implements Renderer<HoveredDialogBodyRenderer.View> {

    public record View(DialogPiece.Spot point, DialogPiece piece, boolean hovered, DialogPiece.Tooltip tooltip,
        HoveredSlotRenderer.Frame frame) {}

    @Override
    public String render(View view) {
        String frame = view.frame() == null ? null : view.frame().width() + "x" + view.frame().height();

        if (view.piece() == null || view.tooltip() == null) {
            return "Hovered " + view.point() + ", where nothing shows a tooltip."
                + (frame == null ? "" : " The " + frame + " frame is attached.");
        }

        List<String> read = DialogPiece.lines(view.tooltip());
        String header = view.hovered()
            ? "Hovered " + view.piece().name() + " at " + view.point() + "."
                + (read.isEmpty() ? " Its tooltip has no lines to draw." : " Its tooltip reads:")
            : "Hovered " + view.piece().name() + " at " + view.point()
                + ", but its widget never took the hover, so no tooltip is drawn."
                + (read.isEmpty() ? "" : " Its lines would read:");

        List<String> lines = new ArrayList<>();
        for (String line : read) {
            lines.add("  " + line);
        }
        if (frame != null) {
            lines.add(view.hovered() && !read.isEmpty()
                ? "The " + frame + " frame attached shows it drawn."
                : "The " + frame + " frame is attached.");
        }
        return lines.isEmpty() ? header : Text.withLines(header, lines);
    }
}
