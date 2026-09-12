package kr.junhyung.mcagents.render;

import tools.jackson.databind.JsonNode;

/**
 * A stack in a numbered slot of the player's own inventory.
 *
 * <p>{@code label} is the custom name a server gave it, and the component beside it is what that
 * name was written as: a quest item or a menu button is named in the resource pack's own font, and
 * a list that only ever said "paper x1" could not tell one from another.
 */
public record Stack(String name, int count, int slot, String label, JsonNode labelComponent) {

    /** The name a reader sees, with the item id after it when a server's name hides it. */
    public static String describe(Stack stack) {
        if (stack.label() == null) {
            return stack.name();
        }
        return Flatten.read(stack.label(), stack.labelComponent()) + " [" + stack.name() + "]";
    }
}
