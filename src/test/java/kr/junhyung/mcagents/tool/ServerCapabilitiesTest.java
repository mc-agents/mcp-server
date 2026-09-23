package kr.junhyung.mcagents.tool;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import kr.junhyung.mcagents.bot.BotSession;
import kr.junhyung.mcagents.catalog.Catalog;
import kr.junhyung.mcagents.catalog.ToolSpec;
import kr.junhyung.mcagents.protocol.Messages;
import org.junit.jupiter.api.Test;

/**
 * What the gate decides from a command tree, which is the whole of what it knows.
 *
 * <p>The three-valued verdict is what these are about. Two of the three refuse and the third does
 * not, and the one that does not is the one worth being sure of: a gate that could not look must
 * let the call through, because every tool that predates it finds out for itself and says so
 * better than a guess would.
 */
class ServerCapabilitiesTest {

    private final Catalog catalog = Catalog.load();

    /** A bot whose command tree is whatever the test says it is. */
    private static final class Tree extends RemoteTools {

        private final Map<String, List<String>> completions;

        Tree(Catalog catalog, Map<String, List<String>> completions) {
            super(catalog);
            this.completions = completions;
        }

        @Override
        Exchange fetch(ToolSpec spec, BotSession bot, Map<String, Object> arguments) {
            String text = (String) arguments.get("text");
            List<String> found = completions.get(text);

            if (found == null) {
                throw new IllegalStateException("nothing completes \"%s\"".formatted(text));
            }
            Map<String, Object> data = Map.of("text", text, "total", found.size(), "completions",
                    found.stream().map(name -> Map.of("name", name)).toList());

            return new Exchange(new Messages.Result(1, true, "", data, List.of(), null, 1), 1_000);
        }
    }

    private ServerCapabilities gate(Map<String, List<String>> tree) {
        return new ServerCapabilities(catalog, new Tree(catalog, tree));
    }

    private BotSession bot(String name, String... offered) {
        BotSession session = new BotSession(name, "fabric", null, null);

        session.accept(new Messages.Status("ready", 1, "paper:25565", name, "26.1.2", "Paper", "creative",
                "overworld", null, 20.0, 20.0, false, null, null, null));
        session.acceptCapabilities(java.util.Arrays.stream(offered)
                .map(tool -> new Messages.Capability(tool, catalog.require(tool).wireSchemaHash()))
                .toList());
        return session;
    }

    @Test
    void aCommandInTheTreeIsPresentAndRefusesNothing() {
        ServerCapabilities gate = gate(Map.of("/craftengine debug ", List.of("setblock", "spawn-furniture")));

        assertNull(gate.refusal(bot("a", "complete-command"), ServerCapabilities.CRAFT_ENGINE_FURNITURE));
    }

    /**
     * Nothing under the command and nothing above it either. The refusal names the plugin, because
     * "the tool did not work" is not something anyone can do anything about.
     */
    @Test
    void aCommandThatCompletesNothingAnywhereIsAbsentAndSaysWhichPluginIsMissing() {
        ServerCapabilities gate = gate(Map.of("/craftengine debug ", List.of(), "/craftengine ", List.of()));
        String refused = gate.refusal(bot("a", "complete-command"), ServerCapabilities.CRAFT_ENGINE_FURNITURE);

        assertNotNull(refused);
        assertTrue(refused.contains("has no CraftEngine"), refused);
    }

    /**
     * The plugin answers and this one command does not, which a server only ever does by withholding
     * it. Telling that from an absent plugin is the whole reason the parent is asked about at all,
     * and the permission node is what makes the answer actionable.
     */
    @Test
    void aCommandMissingFromATreeThatHasItsParentIsUnpermittedAndNamesTheNode() {
        ServerCapabilities gate = gate(Map.of(
                "/craftengine debug ", List.of("setblock"),
                "/craftengine ", List.of("debug", "reload")));
        String refused = gate.refusal(bot("a", "complete-command"), ServerCapabilities.CRAFT_ENGINE_FURNITURE);

        assertNotNull(refused);
        assertTrue(refused.contains("not this bot's to run"), refused);
        assertTrue(refused.contains("ce.command.debug.spawn_furniture"), refused);
        /* The command it names has to be one somebody could type; it was run together once. */
        assertTrue(refused.contains("/craftengine debug spawn-furniture"), refused);
    }

    /**
     * A bot with no completion tool cannot be asked, and an unasked question is not a no. The call
     * goes through and whatever the tool does itself decides -- which is what read-furniture and
     * build-region did before this gate existed, correctly.
     */
    @Test
    void aBotThatCannotBeAskedIsUnknownAndLetsTheCallThrough() {
        ServerCapabilities gate = gate(Map.of());

        assertNull(gate.refusal(bot("a"), ServerCapabilities.CRAFT_ENGINE_FURNITURE));
    }

    /** A tool that names no plugin is never probed, so a bot in no world is not held up by the gate. */
    @Test
    void aToolThatRequiresNothingIsNeverProbed() {
        ServerCapabilities gate = gate(Map.of());

        assertNull(gate.refusal(catalog.require("read-region"), bot("a", "complete-command")));
    }
}
