package dev.mcagents.mcp.bot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * One feed of one bot: a bounded history plus whoever is waiting on it.
 *
 * <p>Two rules carry most of the weight.
 *
 * <p><b>A repeat updates in place and wakes nobody.</b> The bot folds an action bar that repeats
 * every tick into a run and re-sends it under the same sequence number, so the server can answer
 * "is it still showing" without the wire carrying twenty frames a second. Waking waiters on those
 * would make {@code wait-for-action-bar} fire on a line that was already there before the call.
 *
 * <p><b>History outlives the game connection.</b> When a bot is kicked the last three lines before
 * it happened are the most valuable thing in the buffer, so only removing the bot clears it.
 */
public final class EventFeed {

    public static final int CAPACITY = 200;

    private final String name;
    private final ScheduledExecutorService timers;
    private final Deque<FeedEntry> entries = new ArrayDeque<>();
    private final Set<Waiter> waiters = ConcurrentHashMap.newKeySet();

    public EventFeed(String name, ScheduledExecutorService timers) {
        this.name = name;
        this.timers = timers;
    }

    public String name() {
        return name;
    }

    /**
     * Apply an event. Returns true when it opened a new line rather than extending the last one.
     */
    public synchronized boolean accept(FeedEntry entry) {
        FeedEntry last = entries.peekLast();

        if (last != null && last.seq() == entry.seq()) {
            entries.removeLast();
            entries.addLast(entry);
            return false;
        }

        entries.addLast(entry);
        if (entries.size() > CAPACITY) {
            entries.removeFirst();
        }

        for (Waiter waiter : Set.copyOf(waiters)) {
            if (waiter.matches.test(entry) && waiters.remove(waiter)) {
                waiter.settle(new WaitOutcome.Matched(entry));
            }
        }
        return true;
    }

    public synchronized List<FeedEntry> recent(int count) {
        if (count <= 0) {
            return List.of();
        }
        List<FeedEntry> all = new ArrayList<>(entries);
        return List.copyOf(all.subList(Math.max(0, all.size() - count), all.size()));
    }

    public synchronized FeedEntry latest() {
        return entries.peekLast();
    }

    public CompletableFuture<WaitOutcome> waitFor(Predicate<FeedEntry> matches, long timeoutMs) {
        Waiter waiter = new Waiter(matches, System.currentTimeMillis());
        waiters.add(waiter);

        waiter.timeout = timers.schedule(() -> {
            if (waiters.remove(waiter)) {
                waiter.settle(new WaitOutcome.TimedOut(waiter.elapsed()));
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        return waiter.future;
    }

    /**
     * Release everyone waiting because the bot can no longer produce anything. The history stays.
     */
    public void abandon(String reason) {
        for (Waiter waiter : Set.copyOf(waiters)) {
            if (waiters.remove(waiter)) {
                waiter.settle(new WaitOutcome.Abandoned(reason, waiter.elapsed()));
            }
        }
    }

    public synchronized void clear() {
        entries.clear();
    }

    private static final class Waiter {
        private final Predicate<FeedEntry> matches;
        private final long startedAt;
        private final CompletableFuture<WaitOutcome> future = new CompletableFuture<>();
        private volatile java.util.concurrent.ScheduledFuture<?> timeout;

        private Waiter(Predicate<FeedEntry> matches, long startedAt) {
            this.matches = matches;
            this.startedAt = startedAt;
        }

        private long elapsed() {
            return System.currentTimeMillis() - startedAt;
        }

        private void settle(WaitOutcome outcome) {
            java.util.concurrent.ScheduledFuture<?> scheduled = timeout;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            future.complete(outcome);
        }
    }
}
