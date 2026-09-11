package kr.junhyung.mcagents.bot;

/**
 * How far a join got before it failed.
 *
 * <p>The server this replaces answered every one of these with "connection failed", which leaves
 * you unable to tell a pod that never started from a game server that refused the login. They are
 * different problems with different fixes, so the failure says which one happened.
 */
public enum JoinStage {

    /** No pod, or one that never reached the link. Look at the operator and the image. */
    LINK("the bot never linked"),

    /** Linked, but the Minecraft server refused or never answered. Look at the target server. */
    LOGIN("the Minecraft server did not accept the connection"),

    /** Logged in, but never spawned. Usually the world or a plugin holding the player. */
    SPAWN("the bot logged in but never spawned");

    private final String summary;

    JoinStage(String summary) {
        this.summary = summary;
    }

    public String summary() {
        return summary;
    }
}
