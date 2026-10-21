package kr.junhyung.mcagents.render;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Where the open dialog's body can be pressed or hovered.
 *
 * <p>A line per piece, each with its index, its box and the point a cursor reaches it at, because a
 * dialog drawn in glyphs has nothing else to name a piece by. The box and the point are different
 * on purpose: pieces overlap, and the point is the one the client's hit test gives to this piece.
 * One no point reaches says why, which is the answer to "pressed it and nothing happened".
 */
public final class DialogBodyRenderer implements Renderer<DialogBodyRenderer.View> {

    public record Size(int width, int height) {}

    public record Body(int x, int y, int width, int height, double scroll, int maxScroll) {}

    public record Dialog(String title, JsonNode titleComponent, Size gui, Body body, List<DialogPiece> pieces) {}

    public record View(String screen, Dialog dialog) {}

    @Override
    public String render(View view) {
        Dialog dialog = view.dialog();
        if (dialog == null) {
            return view.screen() == null
                ? "No dialog is open, and no other screen either."
                : "No dialog is open; the screen is " + view.screen() + ".";
        }

        String title = Flatten.read(dialog.title(), dialog.titleComponent());
        String screen = "The screen is " + dialog.gui().width() + "x" + dialog.gui().height() + " in GUI units";
        if (dialog.pieces().isEmpty()) {
            return "Dialog \"" + title + "\" has nothing in its body that can be pressed or hovered. " + screen + ".";
        }

        Body body = dialog.body();
        String shown = screen + ", and the body shows " + body.x() + "," + body.y() + " to "
            + (body.x() + body.width()) + "," + (body.y() + body.height())
            + (body.maxScroll() > 0
                ? ", scrolled " + Text.number(body.scroll()) + " of " + body.maxScroll()
                    + " (use-mouse scrolls it)"
                : "")
            + ".";

        List<String> lines = new ArrayList<>();
        for (DialogPiece piece : dialog.pieces()) {
            lines.add("  " + piece.describe());
        }
        int count = dialog.pieces().size();
        return Text.withLines("Dialog \"" + title + "\": " + count + (count == 1 ? " piece" : " pieces")
            + " of its body can be pressed or hovered. " + shown, lines);
    }
}
