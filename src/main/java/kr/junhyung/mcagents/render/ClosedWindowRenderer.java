package kr.junhyung.mcagents.render;

import tools.jackson.databind.JsonNode;

/**
 * What closing a window did, including when there was nothing to close.
 *
 * <p>Asking to close nothing is a no-op, not a mistake, and each kind of bot had its own sentence
 * for refusing it. The state travels and the words are the server's.
 */
public final class ClosedWindowRenderer implements Renderer<ClosedWindowRenderer.View> {

    public record View(String closed, JsonNode closedComponent) {}

    @Override
    public String render(View view) {
        if (view.closed() == null) {
            return "No window was open, so there was nothing to close.";
        }
        return "Closed window \"" + Flatten.read(view.closed(), view.closedComponent()) + "\".";
    }
}
