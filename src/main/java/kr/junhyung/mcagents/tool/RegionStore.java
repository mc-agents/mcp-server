package kr.junhyung.mcagents.tool;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The regions this server is holding, by id.
 *
 * <p>A region an agent reads is worth more kept than answered. Answered, a box of a million
 * blocks is a million characters an agent cannot take in and cannot hand back; kept, it is an id
 * that show-region draws a window of, write-region puts down again, and a download turns into a
 * file. So every read lands here, and the id is what the tools pass around.
 *
 * <p>In memory, for the life of the process. That is the plain truth of where this server runs --
 * a pod with no volume -- and it is stated on every tool that hands out an id. The store is bounded
 * in blocks rather than in regions, since one survey can outweigh fifty rooms, and when it is full
 * the oldest goes: a region read an hour ago has been shown, written or downloaded by now, or it
 * was not wanted.
 */
@Component
public class RegionStore {

    /** Blocks held at once, across every region: two bytes a block, so thirty-two megabytes. */
    static final long MAX_BLOCKS = 16_777_216;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Insertion order is age, and age is what is evicted. */
    private final Map<String, Snapshot> regions = new LinkedHashMap<>();

    private long held;

    /** A fresh id, of the shape every tool here quotes: "r-" and four hex digits. */
    public synchronized String fresh() {
        while (true) {
            byte[] bytes = new byte[2];

            RANDOM.nextBytes(bytes);

            String id = "r-" + HexFormat.of().formatHex(bytes);

            if (!regions.containsKey(id)) {
                return id;
            }
        }
    }

    /**
     * Keep a region, evicting the oldest until it fits.
     *
     * @return the ids that were let go to make room, oldest first
     */
    public synchronized List<String> keep(Snapshot snapshot) {
        if (snapshot.blocks() > MAX_BLOCKS) {
            throw new IllegalArgumentException("%d blocks is more than the %d this server keeps at once"
                    .formatted(snapshot.blocks(), MAX_BLOCKS));
        }

        Snapshot replaced = regions.remove(snapshot.id());

        if (replaced != null) {
            held -= replaced.blocks();
        }

        List<String> evicted = new ArrayList<>();
        Iterator<Map.Entry<String, Snapshot>> oldestFirst = regions.entrySet().iterator();

        while (held + snapshot.blocks() > MAX_BLOCKS && oldestFirst.hasNext()) {
            Map.Entry<String, Snapshot> oldest = oldestFirst.next();

            oldestFirst.remove();
            held -= oldest.getValue().blocks();
            evicted.add(oldest.getKey());
        }

        regions.put(snapshot.id(), snapshot);
        held += snapshot.blocks();

        return evicted;
    }

    public synchronized Snapshot get(String id) {
        return regions.get(id);
    }

    public synchronized Snapshot require(String id) {
        Snapshot found = regions.get(id);

        if (found == null) {
            throw new IllegalArgumentException(
                    "no region is kept as \"%s\". list-regions shows what this server holds; a region is lost when the server restarts or when newer reads have pushed it out."
                            .formatted(id));
        }
        return found;
    }

    /** Oldest first. */
    public synchronized List<Snapshot> all() {
        return List.copyOf(regions.values());
    }

    public synchronized long held() {
        return held;
    }
}
