package kr.junhyung.mcagents.render;

import java.util.List;
import tools.jackson.databind.JsonNode;

public final class ScoreboardRenderer implements Renderer<ScoreboardRenderer.View> {

    /**
     * {@code scoreText} and {@code scoreComponent} are both null from a bot built before the score
     * column was sent as drawn, and the number is all that bot knows then.
     */
    public record Entry(String name, int score, JsonNode nameComponent, String scoreText, JsonNode scoreComponent) {}

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

        return Text.withLines(header, board.entries().stream().map(ScoreboardRenderer::line).toList());
    }

    /**
     * A sidebar drawn one team per line puts the whole line in the team's prefix and suffix, under an
     * owner made of colour codes and a number format that hides the score. Such a line has nothing
     * left to name it once those are gone, and it still takes up a row.
     */
    private static String line(Entry entry) {
        String name = Flatten.read(entry.name(), entry.nameComponent());
        String score = score(entry);

        return "  " + (name.isBlank() ? "(blank)" : name) + (score.isEmpty() ? "" : ": " + score);
    }

    private static String score(Entry entry) {
        boolean noComponent = entry.scoreComponent() == null || entry.scoreComponent().isNull();

        if (entry.scoreText() == null && noComponent) {
            return String.valueOf(entry.score());
        }

        List<Piece> pieces = Flatten.pieces(entry.scoreComponent());
        if (pieces != null && pieces.isEmpty()) {
            return "";
        }
        return Piece.join(pieces, entry.scoreText() == null ? "" : entry.scoreText());
    }
}
