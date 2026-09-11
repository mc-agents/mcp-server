package kr.junhyung.mcagents.render;

import java.util.List;

public final class CompletionsRenderer implements Renderer<CompletionsRenderer.View> {

    public record Completion(String name, String tooltip) {}

    /**
     * {@code total} is what the server offered; {@code completions} is as much of it as the caller asked to see.
     */
    public record View(String text, int total, List<Completion> completions) {}

    @Override
    public String render(View view) {
        if (view.total() == 0) {
            return "The server offered nothing for \"" + view.text() + "\".";
        }

        List<String> lines = view.completions().stream()
            .map(one -> one.tooltip() == null ? "  " + one.name() : "  " + one.name() + " -- " + one.tooltip())
            .toList();

        String more = view.total() > lines.size() ? "\n  ... " + (view.total() - lines.size()) + " more" : "";

        return Text.withLines(
            view.total() + " completions for \"" + view.text() + "\" " + Text.DATA_NOTICE + ":",
            lines) + more;
    }
}
