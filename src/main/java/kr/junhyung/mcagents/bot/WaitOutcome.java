package kr.junhyung.mcagents.bot;

/**
 * Why a wait ended.
 *
 * <p>The distinction matters more than it looks. The tool this replaces resolved to null for both
 * "nothing matched in time" and "the bot went away", so a caller was told a pattern had not
 * appeared in ten seconds when in fact the bot had been kicked after four. Anyone debugging a
 * server then looks for the wrong thing.
 */
public sealed interface WaitOutcome {

    record Matched(FeedEntry entry) implements WaitOutcome {}

    record TimedOut(long waitedMs) implements WaitOutcome {}

    /** The bot stopped being able to produce anything. {@code reason} is the kick text if there was one. */
    record Abandoned(String reason, long waitedMs) implements WaitOutcome {}
}
