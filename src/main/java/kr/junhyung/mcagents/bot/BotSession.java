package kr.junhyung.mcagents.bot;

import kr.junhyung.mcagents.protocol.Messages;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
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

    private volatile Messages.Status status;
    private volatile long lastUsedAt = System.currentTimeMillis();

    public BotSession(String name, String kind, BotLink link, ScheduledExecutorService timers) {
        this.name = name;
        this.kind = kind;
        this.link = link;
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

    public void accept(Messages.Status update) {
        this.status = update;

        if ("disconnected".equals(update.state()) || "faulted".equals(update.state())) {
            String reason = update.reason() != null ? update.reason()
                    : update.lastError() != null ? update.lastError().message()
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
    }

    public void close(String reason) {
        abandonWaiters(reason);
        link.close();
    }
}
