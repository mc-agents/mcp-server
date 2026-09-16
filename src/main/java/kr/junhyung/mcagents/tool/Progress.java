package kr.junhyung.mcagents.tool;

/**
 * What a long call says while it is still running.
 *
 * <p>A join that is waiting for a pod, a wait-for-server that has pinged eleven times, a
 * wait-for-scoreboard that keeps reading the same line: the server knows the stage and the count
 * and, until the call ends, has had no way to say so. MCP's progress notification is that channel,
 * and it exists only when the client asked for one by sending a progress token, so a caller that
 * sent none gets {@link #NONE} and the reporting costs nothing.
 */
@FunctionalInterface
public interface Progress {

    Progress NONE = (message, elapsedMs, totalMs) -> {};

    /**
     * @param message   what the call is doing right now, in the words its failure would use
     * @param elapsedMs how long it has been at it
     * @param totalMs   how long it is prepared to go on for
     */
    void report(String message, long elapsedMs, long totalMs);
}
