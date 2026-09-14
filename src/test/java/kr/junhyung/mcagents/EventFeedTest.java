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
        return new FeedEntry(seq, "chat", "chat", text, List.of(), null, null, now, now, repeats);
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

        FeedEntry line = new FeedEntry(1, "actionBar", "actionbar", "wrong", List.of(), component, null, now, now, 1);

        assertEquals("[hud/bars_text] 20/20 | [hud/money_text] 0", line.rendered());
    }

    /**
     * A separator belongs between the labels of a HUD, not between the words of a sentence. A chat
     * line the server merely coloured came back as "Hello  | world |  and welcome" -- three pieces
     * the player saw as one line -- and an agent checking what a message said would have gone
     * looking for text that is not on the screen.
     */
    @Test
    void aLineStyledOnlyByColourReadsAsTheOneLineItIs() throws Exception {
        JsonNode component = JsonMapper.builder().build().readTree("""
                {"text": "", "extra": [
                  {"text": "Hello ", "color": "red"},
                  {"text": "world", "color": "blue"},
                  {"text": " and welcome"}]}""");
        long now = System.currentTimeMillis();

        FeedEntry line = new FeedEntry(1, "chat", "chat", "wrong", List.of(), component, null, now, now, 1);

        assertEquals("Hello world and welcome", line.rendered());
    }

    /** The same, for a bot that sent pieces instead of a component. */
    @Test
    void segmentsWithNoFontBetweenThemReadAsOneLineToo() {
        long now = System.currentTimeMillis();
        List<FeedEntry.Segment> segments = List.of(
                new FeedEntry.Segment("Hello ", null, "red"),
                new FeedEntry.Segment("world", null, "blue"));

        FeedEntry line = new FeedEntry(1, "chat", "chat", "wrong", segments, null, null, now, now, 1);

        assertEquals("Hello world", line.rendered());
    }

    /*
    A dialog is a title, some body, a row of buttons and a count of inputs: not one piece of text,
    so it travels as structure and the sentence is written here. One kind of bot used to build that
    sentence itself and the other had no dialog feed at all.
    */
    @Test
    void aDialogIsDescribedFromItsStructure() throws Exception {
        JsonNode dialog = JsonMapper.builder().build().readTree("""
                {"type": "minecraft:multi_action",
                 "title": {"text": "", "extra": [
                   {"text": "\uE001", "font": "hyperfarm:gui/icons"},
                   {"text": "Bot check", "font": "hyperfarm:gui/header"}]},
                 "body": [{"type": "minecraft:plain_message",
                           "contents": "Which button did the bot press?"}],
                 "actions": [{"label": "Confirm"}, {"label": "Cancel"}],
                 "exit_action": {"label": "Close"},
                 "inputs": [{"key": "name", "type": "minecraft:text"}]}""");
        long now = System.currentTimeMillis();

        FeedEntry line = new FeedEntry(1, "dialog", "dialog", "wrong", List.of(), null, dialog, now, now, 1);

        assertEquals("[gui/header] Bot check | Which button did the bot press?"
                + " | buttons: Confirm, Cancel, Close | inputs: name (text) = \"\"", line.rendered());
    }

    /*
    What a dialog's inputs hold is what its button will send, so a caller about to press one needs it.
    The bot sends the values once one was set; until then each input holds what the dialog starts it
    at, and a slider with no starting value starts halfway, as the client draws it.
    */
    @Test
    void aDialogsInputsReadWhatTheyHold() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        String inputs = """
                "inputs": [
                  {"key": "notify", "type": "minecraft:boolean", "label": "Tell me"},
                  {"key": "mode", "type": "minecraft:single_option", "label": "Mode",
                   "options": ["slow", {"id": "fast", "display": "Fast", "initial": true}]},
                  {"key": "speed", "type": "minecraft:number_range", "label": "Speed",
                   "start": 0, "end": 10, "step": 2}]""";
        JsonNode shown = mapper.readTree("{\"title\": \"Settings\", " + inputs + "}");
        JsonNode set = mapper.readTree("{\"title\": \"Settings\", " + inputs
                + ", \"values\": {\"notify\": true, \"mode\": \"slow\", \"speed\": 8}}");
        long now = System.currentTimeMillis();

        assertEquals("Settings | inputs: notify (checkbox) = false, mode (one of slow, fast) = fast,"
                + " speed (0 to 10, step 2) = 5",
                new FeedEntry(1, "dialog", "dialog", "Settings", List.of(), null, shown, now, now, 1).rendered());
        assertEquals("Settings | inputs: notify (checkbox) = true, mode (one of slow, fast) = slow,"
                + " speed (0 to 10, step 2) = 8",
                new FeedEntry(2, "dialog", "dialog", "Settings", List.of(), null, set, now, now, 1).rendered());
    }

    /*
    A server marks a quest done by granting an advancement, and draws its title in the pack's own
    font. The id is not on the toast at all, and it is the one part a wait can be written against
    without knowing what the pack's glyphs spell.
    */
    @Test
    void anAdvancementToastCarriesTheIdItsTitleDoesNotShow() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode title = mapper.readTree("""
                {"text": "", "extra": [
                  {"text": "\uE001", "font": "hyperfarm:gui/icons"},
                  {"text": "First Steps", "font": "hyperfarm:gui/label"}]}""");
        JsonNode data = mapper.readTree("""
                {"id": "mcagents:quest/first_steps", "frame": "goal",
                 "description": "Talk to the guide", "descriptionComponent": {"text": "Talk to the guide"}}""");
        long now = System.currentTimeMillis();

        FeedEntry line = new FeedEntry(1, "toast", "advancement", "First Steps", List.of(), title, data, now, now, 1);

        assertEquals("advancement made: [gui/label] First Steps (mcagents:quest/first_steps, goal)"
                + " | Talk to the guide", line.rendered());
    }

    /** The dialog going away is a line of its own, and the words for it are the server's. */
    @Test
    void aClosedDialogSaysSoWhateverTheBotCalledIt() {
        long now = System.currentTimeMillis();

        FeedEntry line = new FeedEntry(1, "dialog", "closed", "the bot's own wording",
                List.of(), null, null, now, now, 1);

        assertEquals("the dialog was closed", line.rendered());
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
