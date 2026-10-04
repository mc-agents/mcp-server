package kr.junhyung.mcagents.tool;

import java.util.Locale;

/**
 * What a login refusal means when the bot had no account to offer.
 *
 * <p>Offline authentication is the default here: a bot sends a username and carries nothing that
 * proves the name is its own, and a server running online-mode will not take it. None of the three
 * refusals that produces mentions authentication. A fabric bot is told "Failed to log in: Invalid
 * session (Try restarting your game and the launcher)", an azalea bot "Failed to verify username!",
 * and either of them behind a proxy that authenticates for the server gets that proxy's own wording
 * -- so an agent reading any of them goes looking at the whitelist, the version, or a launcher that
 * is not running anywhere here, and the one thing the refusal is about is the one thing it does not
 * say.
 *
 * <p>The server knows what the client's sentence cannot: that this bot never had a session to
 * offer. So a refusal in those words is answered with that, and with the two ways out of it. It is
 * a reading and not a proof -- a proxy, a plugin or a whitelist may word a refusal however it
 * likes -- and the sentence says so rather than announcing a cause it cannot see.
 */
final class Authentication {

    /*
    Each refusal by its English and by the translation key it comes from, because a bot with no
    translation for a disconnect reason shows the key, and a reader staring at
    "multiplayer.disconnect.unverified_username" is no better off than one staring at the sentence.
    */

    /** What a vanilla server says to a login it could not verify. */
    private static final String[] UNVERIFIED = {"failed to verify username", "unverified_username"};

    /** What a vanilla client says when its own session call failed, before it sent the login. */
    private static final String[] NO_SESSION = {"invalid session", "invalidsession"};

    /*
    And what a proxy says, which is the arrangement this project tells people to use: a server kept
    unreachable behind a proxy that authenticates for it. Velocity turns an unauthenticated login
    away in its own words, which say what they mean and still do not say it in either of the two
    forms above, so a reading built only on the vanilla pair would have missed the case it
    recommends.
    */
    private static final String[] PROXY = {"only accepts connections from online-mode", "online-mode-only"};

    private static final String OFFLINE =
            "Offline authentication is the default here: a bot sends a username and carries nothing that proves"
                    + " it is its own, and this is what a server that wants a session answers. That is the likeliest"
                    + " reading of it rather than a certain one -- a whitelist, a proxy or a plugin can word a refusal"
                    + " however it likes -- so check it: a server running online-mode=false takes the bot as it is,"
                    + " and the other way in is an azalea bot started signed in to a Microsoft account, which a"
                    + " fabric bot has no mode for.";

    private static final String CLIENT =
            "That line is the bot's own client and not the server, whatever the wording: it tried to open a Mojang"
                    + " session before sending the login, had no account to open one with, and quoted the vanilla"
                    + " launcher's advice. There is no launcher here, so restarting anything will not help.";

    private static final String SERVER =
            "That line is the server's: it took the username, asked Mojang's session service who had logged in under"
                    + " it, and was told nobody had.";

    private static final String PROXY_SAID =
            "That line is a proxy in front of the server, turning away a login that carried no session of its own."
                    + " Where the setting lives is the proxy's own configuration and not the server's"
                    + " server.properties.";

    private Authentication() {
    }

    /**
     * What to add to a login refusal, or null when the refusal says nothing about a session.
     *
     * <p>A login refusal and nothing else: past the login the server has already taken the bot, so
     * who it is was settled, and a sentence about authentication would be pointing at the one part
     * that worked. Which stage a failure reached is the caller's to know, so the caller decides.
     */
    static String note(String refusal) {
        String said = refusal == null ? "" : refusal.toLowerCase(Locale.ROOT);

        if (says(said, NO_SESSION)) {
            return CLIENT + " " + OFFLINE;
        }
        if (says(said, PROXY)) {
            return PROXY_SAID + " " + OFFLINE;
        }
        return says(said, UNVERIFIED) ? SERVER + " " + OFFLINE : null;
    }

    private static boolean says(String refusal, String[] markers) {
        for (String marker : markers) {
            if (refusal.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
