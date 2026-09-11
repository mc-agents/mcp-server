package kr.junhyung.mcagents.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Every bot the server knows, by the name an agent calls it. */
public final class BotRegistry {

    /**
     * A bot name has to survive being a pod name and a Minecraft username, so it is narrower than
     * either. Sixteen characters is the username ceiling and the prefix eats some of it.
     */
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$");

    private final Map<String, BotSession> sessions = new ConcurrentHashMap<>();
    private final int max;

    public BotRegistry(int max) {
        this.max = max;
    }

    public static void requireValidName(String name) {
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "\"%s\" is not a usable bot name. Use letters, digits, underscore or hyphen, starting with a letter or digit, up to 32 characters."
                            .formatted(name));
        }
    }

    public void add(BotSession session) {
        requireValidName(session.name());

        if (sessions.size() >= max) {
            throw new IllegalStateException(
                    "%d bots are already connected, which is the limit. Call leave-server on one first."
                            .formatted(max));
        }
        if (sessions.putIfAbsent(session.name(), session) != null) {
            throw new IllegalStateException(
                    "a bot named \"%s\" is already connected. Pick another name or leave-server first."
                            .formatted(session.name()));
        }
    }

    /**
     * Find the bot a call meant. Naming it is optional while exactly one is connected, which is the
     * common case and saves an argument on every call.
     */
    public BotSession resolve(String name) {
        if (name != null) {
            BotSession found = sessions.get(name);
            if (found == null) {
                throw new IllegalArgumentException("there is no bot named \"%s\". %s".formatted(name, available()));
            }
            return found;
        }

        if (sessions.isEmpty()) {
            throw new IllegalArgumentException("no bots are connected. Call join-server first.");
        }
        if (sessions.size() > 1) {
            throw new IllegalArgumentException(
                    "more than one bot is connected, so \"bot\" is required. %s".formatted(available()));
        }
        return sessions.values().iterator().next();
    }

    public BotSession remove(String name, String reason) {
        BotSession removed = sessions.remove(name);
        if (removed != null) {
            removed.close(reason);
        }
        return removed;
    }

    public List<BotSession> all() {
        return List.copyOf(sessions.values());
    }

    public int size() {
        return sessions.size();
    }

    /** Bots idle longer than the timeout, so a forgotten bot does not sit in a world forever. */
    public List<BotSession> idleSince(long cutoff) {
        List<BotSession> idle = new ArrayList<>();
        for (BotSession session : sessions.values()) {
            if (session.lastUsedAt() < cutoff) {
                idle.add(session);
            }
        }
        return idle;
    }

    public void shutdown(String reason) {
        for (String name : Set.copyOf(sessions.keySet())) {
            remove(name, reason);
        }
    }

    private String available() {
        List<String> names = new ArrayList<>(sessions.keySet());
        return names.isEmpty() ? "No bots are connected."
                : "Connected bots: %s.".formatted(String.join(", ", names));
    }
}
