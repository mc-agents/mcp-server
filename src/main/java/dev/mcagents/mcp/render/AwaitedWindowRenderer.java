package dev.mcagents.mcp.render;

public final class AwaitedWindowRenderer implements Renderer<AwaitedWindowRenderer.View> {

    private static final WindowRenderer WINDOW = new WindowRenderer();

    public record View(String titlePattern, int timeoutMs, WindowRenderer.View window) {}

    @Override
    public String render(View view) {
        if (view.window() != null) {
            return WINDOW.render(view.window());
        }

        String target = view.titlePattern() == null ? "No window" : "No window titled /" + view.titlePattern() + "/";

        return target + " opened within " + view.timeoutMs() + "ms.";
    }
}
