package kr.junhyung.mcagents.tool;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import kr.junhyung.mcagents.bot.FeedEntry;
import org.junit.jupiter.api.Test;

/**
 * What counts as the server turning a command down.
 *
 * <p>Three things do and they look nothing alike, which is why they are decided in one place: a
 * command that is not there, one this player may not run, and one the game could not read. The
 * third was the one that got away -- a teleport with a rotation and no target is refused as an
 * "Incorrect argument", the tool that sent it read no refusal, and every step after it went ahead
 * believing the bot stood where it had been sent.
 */
class CommandsTest {

    private static List<FeedEntry> said(String text) {
        return List.of(new FeedEntry(1, "chat", Commands.SYSTEM, text, List.of(), null, null, 0, 0, 1));
    }

    @Test
    void aCommandTheGameCouldNotReadIsARefusal() {
        assertNotNull(Commands.refused(said("Incorrect argument for command")));
    }

    @Test
    void aCommandThatIsNotThereAndOneThatIsNotAllowedAreBoth() {
        assertNotNull(Commands.refused(said("Unknown command. Type \"/help\" for help.")));
        assertNotNull(Commands.refused(said("You do not have permission to use this command")));
    }

    /**
     * A plugin's ordinary chatter is not a refusal. WorldEdit says what it expected of a pattern
     * while doing exactly what it was asked, and a tool that read that as a refusal would give up
     * on an edit that had already gone through.
     */
    @Test
    void whatAPluginSaysWhileWorkingIsNotARefusal() {
        assertNull(Commands.refused(said("Operation completed: 18 blocks expected")));
        assertNull(Commands.refused(said("Teleported interior to 95.5, 3.0, -16.5")));
    }
}
