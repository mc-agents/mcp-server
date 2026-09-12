package kr.junhyung.mcagents.render;

import java.util.List;

/**
 * One piece of a component, with the glyphs taken out and the font it was drawn in kept.
 *
 * <p>A server draws a HUD by stacking labels: a bar glyph, a spacer, a number, another number.
 * Joined into one string they run together -- two full health bars become "20/2020/20" and a boss
 * bar naming a place, a date and a channel becomes one word. The font is usually the only thing
 * that says which number is which, so it rides along.
 */
public record Piece(String text, String font, String color) {

    /** Empty when the bot sent none, which is what a plain unstyled title looks like. */
    public static String join(List<Piece> pieces, String fallback) {
        if (pieces == null || pieces.isEmpty()) {
            return fallback;
        }
        return String.join(" | ", pieces.stream().map(Piece::describe).toList());
    }

    private static String describe(Piece piece) {
        if (piece.font() == null) {
            return piece.text();
        }
        return "[%s] %s".formatted(piece.font().substring(piece.font().indexOf(':') + 1), piece.text());
    }
}
