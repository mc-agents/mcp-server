package kr.junhyung.mcagents.catalog;

import java.util.Collection;
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
 * @param readOnly  the call changes nothing in the world or on the server. A client that honours
 *                  the hint lets these through without a prompt, which is about half the catalogue
 * @param destructive the call can undo work: a block dug, a command run as op, a bot sent away
 * @param inputSchema what MCP advertises, including the optional {@code bot}
 * @param wireSchema  what a bot receives: no {@code bot}, nothing optional, already clamped
 * @param watches   the reading tool this one polls until its answer matches, for a wait on state
 *                  the bot pushes no feed for. Null for everything else
 * @param requires  what the server the bot is on has to be running for this tool to do anything:
 *                  a plugin the tool drives rather than a part of the game. Empty for a tool that
 *                  only needs a bot. Named here and not in the description so an agent reads it in
 *                  tools/list and the server can refuse before it touches the bot
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
        boolean readOnly,
        boolean destructive,
        boolean needsWorld,
        int defaultDeadlineMs,
        Map<String, Object> inputSchema,
        Map<String, Object> wireSchema,
        String wireSchemaHash,
        String watches,
        List<String> requires) {

    public enum Route {
        LOCAL, RPC, COMPOSE, ORCHESTRATE;

        static Route of(String raw) {
            return valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        }
    }

    public boolean supportedBy(String kind) {
        return kinds.contains(kind);
    }

    /** Whether every kind of bot there is can run it. */
    public boolean isUniversal(Collection<String> allKinds) {
        return kinds.containsAll(allKinds);
    }

    /**
     * The description MCP shows. A tool not every bot can run says which kinds can, appended here
     * rather than written into the catalogue so the list and the sentence cannot drift apart.
     *
     * <p>Against the kinds the catalogue actually has, not against a count. While there were two,
     * every tool either ran on both or said so; with one there is nothing to distinguish, and
     * appending "supported by bots of kind: fabric" to all sixty-four descriptions would be that
     * many lines of an agent's context spent saying nothing.
     */
    public String advertisedDescription(Collection<String> allKinds) {
        if (isUniversal(allKinds)) {
            return description;
        }
        return "%s Supported by bots of kind: %s.".formatted(description, String.join(", ", kinds));
    }
}
