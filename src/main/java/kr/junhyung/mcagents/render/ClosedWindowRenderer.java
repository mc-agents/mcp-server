package kr.junhyung.mcagents.render;

import tools.jackson.databind.JsonNode;

/**
 * What closing a window did, including when there was nothing to close.
 *
 * <p>Asking to close nothing is a no-op, not a mistake, and each kind of bot had its own sentence
 * for refusing it. The state travels and the words are the server's.
 */
public final class ClosedWindowRenderer implements Renderer<ClosedWindowRenderer.View> {

    public record View(String closed, JsonNode closedComponent, String screen) {}

    @Override
    public String render(View view) {
        if (view.closed() == null) {
            return "No window was open, so there was nothing to close.";
        }

        String title = Flatten.read(view.closed(), view.closedComponent());

        /* A book or a sign editor usually has no title of its own, and "Closed the book" is enough. */
        if (view.screen() != null) {
            return "Closed the " + view.screen() + (title.isBlank() ? "" : " \"" + title + "\"") + ".";
        }
        return "Closed window \"" + title + "\".";
    }
}
