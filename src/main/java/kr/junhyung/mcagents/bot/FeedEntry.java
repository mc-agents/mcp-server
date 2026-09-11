package kr.junhyung.mcagents.bot;

import java.util.List;

/**
 * One line of a feed, as the server keeps it.
 *
 * @param seq       the bot's sequence number. A repeat of a folded run arrives under the same one
 * @param source    where it came from within the feed: {@code chat}/{@code system}, {@code title}/
 *                  {@code subtitle}, {@code sound}/{@code particle}
 * @param text      rendered from {@code segments} when the bot sent any
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
}
