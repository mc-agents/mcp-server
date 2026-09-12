package kr.junhyung.mcagents.render;

import java.util.List;
import tools.jackson.databind.JsonNode;

public final class ScoreboardRenderer implements Renderer<ScoreboardRenderer.View> {

    public record Entry(String name, int score, JsonNode nameComponent) {}

    public record Board(String title, List<Entry> entries, JsonNode titleComponent) {}

    public record View(String slot, Board board) {}

    @Override
    public String render(View view) {
        Board board = view.board();

        if (board == null) {
            return "No scoreboard is displayed in the " + view.slot() + " slot.";
        }

        String read = Flatten.read(board.title(), board.titleComponent());
        String title = read.isEmpty() ? "(untitled)" : read;
        String header = "scoreboard \"" + title + "\" (" + view.slot() + ", " + board.entries().size() + " entries)";

        if (board.entries().isEmpty()) {
            return header + "\nno entries are on it";
        }

        return Text.withLines(header, board.entries().stream()
            .map(entry -> "  " + Flatten.read(entry.name(), entry.nameComponent()) + ": " + entry.score())
            .toList());
    }
}
