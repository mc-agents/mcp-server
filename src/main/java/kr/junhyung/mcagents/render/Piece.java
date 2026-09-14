package kr.junhyung.mcagents.render;

import java.util.List;
import java.util.stream.Collectors;

/**
 * One piece of a component, with the glyphs taken out and the font it was drawn in kept.
 *
 * <p>A server draws a HUD by stacking labels: a bar glyph, a spacer, a number, another number.
 * Joined into one string they run together -- two full health bars become "20/2020/20" and a boss
 * bar naming a place, a date and a channel becomes one word. The font is usually the only thing
 * that says which number is which, so it rides along.
 */
public record Piece(String text, String font, String color) {

    /**
     * What a reader is shown.
     *
     * <p>A separator only where a font says the pieces are separate labels. Without one there is no
     * glyph-drawn HUD to take apart: the pieces are one run of text the server happened to colour,
     * and the screen shows them touching. Separating those said a chat line reading "Hello world
     * and welcome" was "Hello  | world |  and welcome", which is a sentence the player never saw --
     * and both kinds of bot said it, so the comparison suite called them identical and was right
     * about the wrong thing.
     *
     * <p>Empty when the bot sent none, which is what a plain unstyled title looks like.
     */
    public static String join(List<Piece> pieces, String fallback) {
        if (pieces == null || pieces.isEmpty()) {
            return fallback;
        }
        if (pieces.stream().noneMatch(piece -> piece.font() != null)) {
            return pieces.stream().map(Piece::text).collect(Collectors.joining());
        }
        /* Between labels a space separates nothing the " | " does not already. */
        return String.join(" | ", pieces.stream().filter(piece -> !piece.text().isBlank()).map(Piece::describe).toList());
    }

    private static String describe(Piece piece) {
        if (piece.font() == null) {
            return piece.text();
        }
        return "[%s] %s".formatted(piece.font().substring(piece.font().indexOf(':') + 1), piece.text());
    }
}
