package kr.junhyung.mcagents.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A bot is named like a player and a Kubernetes object is not. The cluster refused every bot name with
 * an underscore or a capital, and a mapping that folds case alone would hand two bots one object.
 */
class BotResourceNameTest {

    private static final Pattern RFC_1123_LABEL = Pattern.compile("^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$");

    @Test
    void aNameKubernetesTakesIsUsedAsItIsAndNeedsNoSpecName() {
        assertEquals("qa-talk-1", BotResourceName.of("qa-talk-1"));
        assertNull(BotResourceName.specBotName("qa-talk-1"));
    }

    @Test
    void aPlayerStyleNameGetsAValidObjectNameAndTravelsInTheSpec() {
        String object = BotResourceName.of("Qa_Bot1");

        assertTrue(RFC_1123_LABEL.matcher(object).matches(), object);
        assertTrue(object.startsWith("qa-bot1-"), object);
        assertEquals("Qa_Bot1", BotResourceName.specBotName("Qa_Bot1"));
    }

    @Test
    void namesThatFoldTogetherStillGetObjectsOfTheirOwn() {
        List<String> alike = List.of("Qa_Bot1", "qa_bot1", "QA_BOT1", "qa_Bot1");

        assertEquals(alike.size(), alike.stream().map(BotResourceName::of).distinct().count());
        assertNotEquals(BotResourceName.of("qa-bot1"), BotResourceName.of("qa_bot1"));
    }

    @Test
    void underscoresAtTheEdgesStillMakeAValidName() {
        for (String name : List.of("_bot_", "___", "_1", "A_")) {
            String object = BotResourceName.of(name);
            assertTrue(RFC_1123_LABEL.matcher(object).matches(), name + " -> " + object);
        }
    }

    /** The operator dials in with at most sixteen characters and refuses a hyphen in spec.botName. */
    @Test
    void aNameTheOperatorCouldNotDialInWithIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> BotResourceName.of("Qa-Bot"));
        assertThrows(IllegalArgumentException.class, () -> BotResourceName.of("a_name_longer_than_16"));
        assertThrows(IllegalArgumentException.class, () -> BotResourceName.of("qa-talk-extended-1"));
    }

    @Test
    void anObjectStandsForTheBotItsSpecNamesElseItsOwnNameAsTheOperatorCutsIt() {
        assertEquals("Qa_Bot1", BotResourceName.declared(BotResourceName.of("Qa_Bot1"), "Qa_Bot1"));
        assertEquals("qa-talk-1", BotResourceName.declared("qa-talk-1", null));
        assertEquals("abcdefghijklmnop", BotResourceName.declared("abcdefghijklmnopqrst", ""));
    }
}
