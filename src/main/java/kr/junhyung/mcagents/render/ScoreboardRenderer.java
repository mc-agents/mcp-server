package kr.junhyung.mcagents.render;

import java.util.List;

public final class ScoreboardRenderer implements Renderer<ScoreboardRenderer.View> {

    public record Entry(String name, int score) {}

    public record Board(String title, List<Entry> entries) {}

    public record View(String slot, Board board) {}

    @Override
    public String render(View view) {
        Board board = view.board();

        if (board == null) {
            return "No scoreboard is displayed in the " + view.slot() + " slot.";
        }

        String title = board.title().isEmpty() ? "(untitled)" : board.title();
        String header = "scoreboard \"" + title + "\" (" + view.slot() + ", " + board.entries().size() + " entries)";

        if (board.entries().isEmpty()) {
            return header + "\nno entries are on it";
        }

        return Text.withLines(header, board.entries().stream()
            .map(entry -> "  " + entry.name() + ": " + entry.score())
            .toList());
    }
}
