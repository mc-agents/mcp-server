package kr.junhyung.mcagents.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The two refusals an online-mode server produces are the ones this has to recognise, so they are
 * written here exactly as the bots hand them over -- a fabric bot's with the prefix its own code
 * puts on a failed connect. A marker that stops matching the real sentence fails here rather than
 * sending the next reader back to the launcher.
 */
class AuthenticationTest {

    private static final String FABRIC =
            "the server refused the connection: Failed to log in: Invalid session (Try restarting your game and the launcher)";

    private static final String AZALEA = "Failed to verify username!";

    @Test
    void bothRefusalsAreAnsweredWithTheLimitAndTheWayOutOfIt() {
        for (String refusal : new String[] {FABRIC, AZALEA}) {
            String note = Authentication.note(refusal);

            assertNotNull(note, refusal);
            assertTrue(note.contains("Offline authentication"), note);
            assertTrue(note.contains("online-mode=false") && note.contains("Microsoft account"), note);
        }
    }

    /**
     * A fabric bot's line is its own client's, written before it sent anything, and it is the one
     * that names a launcher. Answering it with "the server said this" would agree with the prefix
     * and keep the reader looking at the server, which is not where that sentence came from.
     */
    @Test
    void theClientsOwnRefusalIsNotPutDownToTheServer() {
        String fabric = Authentication.note(FABRIC);
        String azalea = Authentication.note(AZALEA);

        assertTrue(fabric.contains("There is no launcher here"), fabric);
        assertTrue(azalea.contains("That line is the server's"), azalea);
        assertFalse(azalea.contains("launcher"), azalea);
    }

    /**
     * A proxy that authenticates for the server, which is the arrangement the bot's own README
     * recommends to whoever cannot leave their server unauthenticated. It words the refusal itself,
     * in neither of the vanilla forms, so a reading built on those two alone would have gone quiet
     * on the case the project tells people to build.
     */
    @Test
    void aProxyThatTurnsAwayAnUnauthenticatedLoginIsReadTheSameWay() {
        String proxied = Authentication.note(
                "This server only accepts connections from online-mode clients.");

        assertNotNull(proxied);
        assertTrue(proxied.contains("proxy"), proxied);
        /* The server's own file is the wrong place to send anyone: the proxy holds this setting. */
        assertTrue(proxied.contains("proxy's own configuration"), proxied);
        assertNotNull(Authentication.note("velocity.error.online-mode-only"));
    }

    /** A bot with no translation for the reason shows the key, and the key is no clearer. */
    @Test
    void theTranslationKeysReadTheSameAsTheSentences() {
        assertNotNull(Authentication.note("multiplayer.disconnect.unverified_username"));
        assertNotNull(Authentication.note("disconnect.loginFailedInfo.invalidSession"));
    }

    /**
     * The other causes of a login refusal are left alone. Offering authentication for every one of
     * them would be the same unhelpful guess the client's message makes, pointed the other way --
     * and an outdated client is the one most easily mistaken for it.
     */
    @Test
    void aRefusalThatIsAboutSomethingElseIsLeftAsItIs() {
        assertNull(Authentication.note("You are not whitelisted on this server!"));
        assertNull(Authentication.note("You are banned from this server"));
        assertNull(Authentication.note("The server is full!"));
        assertNull(Authentication.note("Outdated client! Please use 26.1.2"));
        assertNull(Authentication.note(null));
    }
}
