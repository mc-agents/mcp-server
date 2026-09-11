package kr.junhyung.mcagents.bot;

import kr.junhyung.mcagents.protocol.Messages;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * One bot: its link, what it can do, what it last told us, and the five feeds it fills.
 *
 * <p>The session outlives the game connection. A bot that has been kicked still answers
 * {@code read-chat} with the lines that led up to it, which is usually where the reason is.
 */
public final class BotSession {

    /** The feeds a bot fills. Fixed: a tool that reads one of these must not depend on a bot. */
    public static final List<String> FEEDS = List.of("chat", "actionBar", "title", "dialog", "effect");

    private final String name;
    private final String kind;
    private final BotLink link;
    private final Map<String, EventFeed> feeds = new ConcurrentHashMap<>();
    private final Map<String, String> capabilities = new ConcurrentHashMap<>();
    private final long joinedAt = System.currentTimeMillis();

    private final Set<StatusWaiter> statusWaiters = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timers;

    private volatile Messages.Status status;
    private volatile long lastUsedAt = System.currentTimeMillis();

    public BotSession(String name, String kind, BotLink link, ScheduledExecutorService timers) {
        this.name = name;
        this.kind = kind;
        this.link = link;
        this.timers = timers;
        for (String feed : FEEDS) {
            feeds.put(feed, new EventFeed(feed, timers));
        }
    }

    public String name() {
        return name;
    }

    public String kind() {
        return kind;
    }

    public BotLink link() {
        return link;
    }

    public Messages.Status status() {
        return status;
    }

    public long joinedAt() {
        return joinedAt;
    }

    public long lastUsedAt() {
        return lastUsedAt;
    }

    public void acceptCapabilities(List<Messages.Capability> reported) {
        capabilities.clear();
        for (Messages.Capability capability : reported) {
            capabilities.put(capability.tool(), capability.argsHash());
        }
    }

    public int capabilityCount() {
        return capabilities.size();
    }

    public boolean supports(String tool) {
        return capabilities.containsKey(tool);
    }

    public String argsHashFor(String tool) {
        return capabilities.get(tool);
    }

    /** Drop a tool this bot turned out not to have, so the next call is refused before it is sent. */
    public void withdraw(String tool) {
        capabilities.remove(tool);
    }

    public EventFeed feed(String name) {
        EventFeed found = feeds.get(name);
        if (found == null) {
            throw new IllegalArgumentException("there is no feed called \"%s\"".formatted(name));
        }
        return found;
    }

    public CompletableFuture<WaitOutcome> waitOn(String feed, Predicate<FeedEntry> matches, long timeoutMs) {
        return feed(feed).waitFor(matches, timeoutMs);
    }

    public void accept(Messages.Event event) {
        EventFeed target = feeds.get(event.kind());
        if (target == null) {
            return;
        }
        target.accept(new FeedEntry(
                event.seq(),
                event.source(),
                event.text(),
                event.segments() == null ? List.of() : event.segments().stream()
                        .map(s -> new FeedEntry.Segment(s.text(), s.font(), s.color()))
                        .toList(),
                event.firstTs(),
                event.ts(),
                Math.max(1, event.repeats())));
    }

    /**
     * Wait for the bot to report a state, which is how join-server knows a login turned into a
     * spawn. A status that already satisfies the test settles at once: the bot may well have got
     * there before the caller started looking, and a wait that missed it would time out on
     * something that had already happened.
     */
    public CompletableFuture<Messages.Status> awaitStatus(Predicate<Messages.Status> matches, long timeoutMs) {
        Messages.Status now = status;

        if (now != null && matches.test(now)) {
            return CompletableFuture.completedFuture(now);
        }

        StatusWaiter waiter = new StatusWaiter(matches);
        statusWaiters.add(waiter);

        waiter.timeout = timers.schedule(() -> {
            if (statusWaiters.remove(waiter)) {
                waiter.settle(null);
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        return waiter.future;
    }

    public void accept(Messages.Status update) {
        this.status = update;

        for (StatusWaiter waiter : Set.copyOf(statusWaiters)) {
            if (waiter.matches.test(update) && statusWaiters.remove(waiter)) {
                waiter.settle(update);
            }
        }

        if ("disconnected".equals(update.state()) || "faulted".equals(update.state())) {
            String reason = update.reason() != null ? update.reason()
                    : update.lastError() != null ? update.lastError()
                    : "the bot disconnected";
            abandonWaiters(reason);
        }
    }

    public void touch() {
        lastUsedAt = System.currentTimeMillis();
    }

    public boolean isReady() {
        Messages.Status current = status;
        return current != null && "ready".equals(current.state());
    }

    /**
     * Release everyone waiting, because nothing more will arrive. The buffers stay: the last lines
     * before a bot went away are the ones worth reading.
     */
    public void abandonWaiters(String reason) {
        for (EventFeed feed : feeds.values()) {
            feed.abandon(reason);
        }
        for (StatusWaiter waiter : Set.copyOf(statusWaiters)) {
            if (statusWaiters.remove(waiter)) {
                waiter.settle(null);
            }
        }
    }

    public void close(String reason) {
        abandonWaiters(reason);
        link.close();
    }

    /** Settles with null when nothing matched, which the caller reads as "it never got there". */
    private static final class StatusWaiter {
        private final Predicate<Messages.Status> matches;
        private final CompletableFuture<Messages.Status> future = new CompletableFuture<>();
        private volatile ScheduledFuture<?> timeout;

        private StatusWaiter(Predicate<Messages.Status> matches) {
            this.matches = matches;
        }

        private void settle(Messages.Status status) {
            ScheduledFuture<?> scheduled = timeout;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            future.complete(status);
        }
    }
}
