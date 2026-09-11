package kr.junhyung.mcagents.render;

/**
 * A window, or the fact that there is not one.
 *
 * <p>"No window is open" is a state, so it is a DTO with {@code window: null} and this sentence is
 * the server's. Both bots used to throw their own wording instead, and the two did not match:
 * comparing the kinds is what found it.
 */
public final class OpenWindowRenderer implements Renderer<OpenWindowRenderer.View> {

    private static final WindowRenderer WINDOW = new WindowRenderer();

    public record View(WindowRenderer.View window) {}

    @Override
    public String render(View view) {
        if (view.window() != null) {
            return WINDOW.render(view.window());
        }
        return "No window is open. Run whatever opens the menu first, then wait-for-window.";
    }
}
