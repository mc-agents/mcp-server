package kr.junhyung.mcagents.render;

/**
 * A bot sends what it knows about the game; the sentence the agent reads is written here. Two kinds
 * of bot cannot describe the same scoreboard two ways if neither of them does the describing.
 */
public interface Renderer<T> {

    String render(T view);
}
