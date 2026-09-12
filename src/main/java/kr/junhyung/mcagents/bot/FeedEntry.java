package kr.junhyung.mcagents.bot;

import java.util.List;
import kr.junhyung.mcagents.render.Dialogs;
import kr.junhyung.mcagents.render.Flatten;
import kr.junhyung.mcagents.render.Piece;
import tools.jackson.databind.JsonNode;

/**
 * One line of a feed, as the server keeps it.
 *
 * @param seq       the bot's sequence number. A repeat of a folded run arrives under the same one
 * @param kind      which feed it belongs to, because what a line reads like depends on it
 * @param source    where it came from within the feed: {@code chat}/{@code system}, {@code title}/
 *                  {@code subtitle}, {@code sound}/{@code particle}
 * @param text      the bot's own flattening, kept as a fallback for a bot that sent no segments
 * @param segments  the pieces a custom-font HUD is drawn from, kept apart
 * @param component the component the bot was given, which the server flattens itself
 * @param data      structure a sentence cannot hold: the dialog feed sends the dialog itself
 * @param firstSeen when this run started
 * @param seen      when it was last observed. A run that is still showing keeps moving this
 * @param repeats   how many times it has been observed, 1 or more
 */
public record FeedEntry(
        long seq,
        String kind,
        String source,
        String text,
        List<Segment> segments,
        JsonNode component,
        JsonNode data,
        long firstSeen,
        long seen,
        int repeats) {

    public record Segment(String text, String font, String color) {}

    /**
     * What a reader is shown.
     *
     * <p>Built from the component, or from the segments when the bot could not send one: a server
     * drawing a HUD stacks a bar glyph, a spacer and a label, and flattening those runs the labels
     * together -- two bars both reading 20/20 arrive as "20/2020/20". {@link Piece#join} decides
     * where a separator belongs, so the two kinds of bot cannot each pick one.
     *
     * <p>A bot that sent neither keeps its own text, which is every feed that is one piece.
     */
    public String rendered() {
        if (Dialogs.FEED.equals(kind)) {
            return Dialogs.describe(source, data, text);
        }

        List<Piece> flattened = Flatten.pieces(component);

        if (flattened != null && !flattened.isEmpty()) {
            return Piece.join(flattened, text);
        }
        if (segments.isEmpty()) {
            return text;
        }
        return Piece.join(segments.stream().map(FeedEntry::piece).toList(), text);
    }

    private static Piece piece(Segment segment) {
        return new Piece(segment.text(), segment.font(), segment.color());
    }
}
