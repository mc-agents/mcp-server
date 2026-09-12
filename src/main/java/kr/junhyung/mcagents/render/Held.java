package kr.junhyung.mcagents.render;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * A stack somewhere other than a numbered slot: under the cursor, or on its way to the ground.
 *
 * <p>Separate from {@link WindowRenderer.Slot} because that one carries the slot it sits in, and a
 * stack the cursor holds sits in none.
 */
public record Held(String name, int count, String label, List<String> lore,
    JsonNode labelComponent, List<JsonNode> loreComponents) {

    /** Empty is a state, and a bot reports it by sending no stack at all rather than a count of 0. */
    public static String describe(Held held) {
        if (held == null) {
            return "empty";
        }

        String named = held.label() == null
            ? held.name()
            : Flatten.read(held.label(), held.labelComponent()) + " [" + held.name() + "]";

        return named + " x" + held.count();
    }
}
