package kr.junhyung.mcagents.catalog;

import java.util.List;
import java.util.Map;

/**
 * One tool as the catalogue declares it.
 *
 * @param route     where a call goes: {@code local} the server answers alone, {@code rpc} a bot
 *                  does, {@code compose} both, {@code orchestrate} the bot's lifecycle
 * @param kinds     which kinds of bot can run it. A tool only one kind supports says so in its
 *                  description, generated rather than written, so the two cannot disagree
 * @param exclusive one at a time per bot. Two concurrent walks cancel each other and then both lie
 * @param untrusted the answer contains text a server or a player wrote, so the server wraps it
 * @param structured the bot sends a DTO and the server renders it, so two bots cannot describe the
 *                  same state differently
 * @param inputSchema what MCP advertises, including the optional {@code bot}
 * @param wireSchema  what a bot receives: no {@code bot}, nothing optional, already clamped
 */
public record ToolSpec(
        String name,
        String group,
        String description,
        Route route,
        List<String> kinds,
        boolean exclusive,
        boolean untrusted,
        boolean structured,
        boolean needsWorld,
        int defaultDeadlineMs,
        Map<String, Object> inputSchema,
        Map<String, Object> wireSchema,
        String wireSchemaHash) {

    public enum Route {
        LOCAL, RPC, COMPOSE, ORCHESTRATE;

        static Route of(String raw) {
            return valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        }
    }

    public boolean supportedBy(String kind) {
        return kinds.contains(kind);
    }

    public boolean isUniversal() {
        return kinds.size() > 1;
    }

    /**
     * The description MCP shows. A tool not every bot can run says which kinds can, appended here
     * rather than written into the catalogue so the list and the sentence cannot drift apart.
     */
    public String advertisedDescription() {
        if (isUniversal()) {
            return description;
        }
        return "%s Supported by bots of kind: %s.".formatted(description, String.join(", ", kinds));
    }
}
