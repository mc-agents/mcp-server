package kr.junhyung.mcagents.bot;

import java.util.List;

/**
 * One line of a feed, as the server keeps it.
 *
 * @param seq       the bot's sequence number. A repeat of a folded run arrives under the same one
 * @param source    where it came from within the feed: {@code chat}/{@code system}, {@code title}/
 *                  {@code subtitle}, {@code sound}/{@code particle}
 * @param text      the bot's own flattening, kept as a fallback for a bot that sent no segments
 * @param segments  the pieces a custom-font HUD is drawn from, kept apart
 * @param firstSeen when this run started
 * @param seen      when it was last observed. A run that is still showing keeps moving this
 * @param repeats   how many times it has been observed, 1 or more
 */
public record FeedEntry(
        long seq,
        String source,
        String text,
        List<Segment> segments,
        long firstSeen,
        long seen,
        int repeats) {

    public record Segment(String text, String font, String color) {}

    /**
     * What a reader is shown.
     *
     * <p>Built from the segments, because that is the whole reason they travel apart: a server
     * drawing a HUD stacks a bar glyph, a spacer and a label, and flattening those runs the labels
     * together -- two bars both reading 20/20 arrive as "20/2020/20". The separator is the
     * server's so the two kinds of bot cannot each pick one.
     *
     * <p>A bot that sent no segments keeps its own text, which is every feed that is one piece.
     */
    public String rendered() {
        if (segments.isEmpty()) {
            return text;
        }
        return segments.stream().map(FeedEntry::describe).reduce((a, b) -> a + " | " + b).orElse(text);
    }

    /** The font is usually the only thing saying which number is which, so it rides along. */
    private static String describe(Segment segment) {
        if (segment.font() == null) {
            return segment.text();
        }
        int colon = segment.font().indexOf(':');
        return "[%s] %s".formatted(segment.font().substring(colon + 1), segment.text());
    }
}
