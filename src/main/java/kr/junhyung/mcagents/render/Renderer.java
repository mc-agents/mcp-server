package kr.junhyung.mcagents.render;

import java.util.Map;

/**
 * A bot sends what it knows about the game; the sentence the agent reads is written here. Two kinds
 * of bot cannot describe the same scoreboard two ways if neither of them does the describing.
 */
public interface Renderer<T> {

    String render(T view);

    /**
     * The same sentence, shaped by arguments that never reached the bot. A renderer that offers
     * none ignores them; read-window's {@code part} and {@code lore} are the ones that exist.
     */
    default String render(T view, Map<String, Object> arguments) {
        return render(view);
    }
}
