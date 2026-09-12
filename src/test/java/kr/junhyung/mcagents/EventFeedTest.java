package kr.junhyung.mcagents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import kr.junhyung.mcagents.bot.EventFeed;
import kr.junhyung.mcagents.bot.FeedEntry;
import kr.junhyung.mcagents.bot.WaitOutcome;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EventFeedTest {

    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    private final EventFeed feed = new EventFeed("chat", timers);

    @AfterEach
    void stopTimers() {
        timers.shutdownNow();
    }

    private static FeedEntry entry(long seq, String text, int repeats) {
        long now = System.currentTimeMillis();
        return new FeedEntry(seq, "chat", text, List.of(), null, now, now, repeats);
    }

    /**
     * The pieces come from the component when there is one, which is the whole reason it travels:
     * the flattening rule lives in the server and a bot that sends the component does not have to
     * be right about fonts, nesting or the shorthand.
     */
    @Test
    void aLineWithAComponentIsFlattenedFromIt() throws Exception {
        JsonNode component = JsonMapper.builder().build().readTree("""
                {"text": "", "extra": [
                  {"text": "20/20", "font": "hyperfarm:hud/bars_text"},
                  {"text": "\uE001"},
                  {"text": "0", "font": "hyperfarm:hud/money_text"}]}""");
        long now = System.currentTimeMillis();

        FeedEntry line = new FeedEntry(1, "actionbar", "wrong", List.of(), component, now, now, 1);

        assertEquals("[hud/bars_text] 20/20 | [hud/money_text] 0", line.rendered());
    }

    @Test
    void theNewestLinesComeBackWithinTheRequestedCount() {
        for (int i = 0; i < 5; i++) {
            feed.accept(entry(i, "line " + i, 1));
        }

        assertEquals(List.of("line 3", "line 4"), feed.recent(2).stream().map(FeedEntry::text).toList());
        assertEquals(0, feed.recent(0).size());
        assertEquals(5, feed.recent(100).size());
    }

    @Test
    void theBufferStopsGrowingOnceItIsFull() {
        for (int i = 0; i < EventFeed.CAPACITY + 50; i++) {
            feed.accept(entry(i, "line " + i, 1));
        }

        List<FeedEntry> kept = feed.recent(EventFeed.CAPACITY);
        assertEquals(EventFeed.CAPACITY, kept.size());
        assertEquals("line " + (EventFeed.CAPACITY + 49), kept.getLast().text());
    }

    @Test
    void aRepeatUnderTheSameSequenceUpdatesInPlace() {
        assertTrue(feed.accept(entry(1, "Mana 40/40", 1)));
        assertFalse(feed.accept(entry(1, "Mana 40/40", 2)));
        assertFalse(feed.accept(entry(1, "Mana 40/40", 3)));

        assertEquals(1, feed.recent(10).size());
        assertEquals(3, feed.latest().repeats());
    }

    /*
    This is the contract that is easiest to break and hardest to notice: a line that was already
    showing when the wait began must not satisfy it.
    */
    @Test
    void aRepeatDoesNotWakeAWaiter() throws Exception {
        feed.accept(entry(1, "Quest complete", 1));

        CompletableFuture<WaitOutcome> waiting =
                feed.waitFor(line -> line.text().equals("Quest complete"), 200);
        feed.accept(entry(1, "Quest complete", 2));

        assertInstanceOf(WaitOutcome.TimedOut.class, waiting.get(2, TimeUnit.SECONDS));
        assertEquals("Quest complete", feed.latest().text());
    }

    @Test
    void aNewLineWakesTheWaiterThatMatchesIt() throws Exception {
        CompletableFuture<WaitOutcome> waiting = feed.waitFor(line -> line.text().contains("island"), 2000);

        feed.accept(entry(1, "nothing to see", 1));
        feed.accept(entry(2, "your island is ready", 1));
        feed.accept(entry(3, "your island is ready again", 1));

        WaitOutcome.Matched matched = assertInstanceOf(WaitOutcome.Matched.class, waiting.get(2, TimeUnit.SECONDS));
        assertEquals("your island is ready", matched.entry().text());
    }

    @Test
    void aWaiterIgnoresWhatArrivedBeforeItStarted() throws Exception {
        feed.accept(entry(1, "already here", 1));

        CompletableFuture<WaitOutcome> waiting = feed.waitFor(line -> line.text().equals("already here"), 150);

        assertInstanceOf(WaitOutcome.TimedOut.class, waiting.get(2, TimeUnit.SECONDS));
    }

    /*
    The improvement this type exists for. Being kicked after four seconds and matching nothing in
    ten are different facts, and telling a caller the wrong one sends them looking in the wrong place.
    */
    @Test
    void abandoningSaysTheBotWentAwayRatherThanThatTimeRanOut() throws Exception {
        CompletableFuture<WaitOutcome> waiting = feed.waitFor(line -> false, 60_000);

        feed.abandon("kicked: You have been idle for too long");

        WaitOutcome.Abandoned abandoned =
                assertInstanceOf(WaitOutcome.Abandoned.class, waiting.get(2, TimeUnit.SECONDS));
        assertEquals("kicked: You have been idle for too long", abandoned.reason());
        assertTrue(abandoned.waitedMs() < 60_000);
    }

    @Test
    void abandoningKeepsTheHistoryBecauseTheLastLinesAreWhyItHappened() {
        feed.accept(entry(1, "You have been idle for too long", 1));

        feed.abandon("kicked");

        assertEquals("You have been idle for too long", feed.latest().text());
    }
}
