package kr.junhyung.mcagents.render;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * A stack in a numbered slot of the player's own inventory.
 *
 * <p>{@code label} is the custom name a server gave it, and the component beside it is what that
 * name was written as: a quest item or a menu button is named in the resource pack's own font, and
 * a list that only ever said "paper x1" could not tell one from another. {@code itemModel} is the
 * other half of that, since a server that draws its own item draws it with a model of its own.
 *
 * <p>The fields after the label are boxed and may be missing, because a bot built before they
 * existed sends none of them and its inventory still has to read.
 */
public record Stack(String name, int count, int slot, String label, JsonNode labelComponent,
    List<String> lore, List<JsonNode> loreComponents, String itemModel, Integer cooldownTicks) {

    /** The name a reader sees, with the item id after it when a server's name hides it. */
    public static String describe(Stack stack) {
        if (stack.label() == null) {
            return stack.name();
        }
        return Flatten.read(stack.label(), stack.labelComponent()) + " [" + stack.name() + "]";
    }

    /**
     * What the slot number is followed by: a model other than the item's own, and a cooldown. Both
     * are left out when there is nothing to say, so a plain stack reads as it always did.
     */
    static List<String> notes(Stack stack) {
        List<String> notes = new ArrayList<>();
        String model = model(stack.name(), stack.itemModel());

        if (model != null) {
            notes.add("model " + model);
        }
        if (stack.cooldownTicks() != null) {
            notes.add("cooling down for " + stack.cooldownTicks() + " more ticks");
        }
        return notes;
    }

    /** The lore under a stack, indented the way a window lists it. */
    static String lore(Stack stack) {
        List<String> lines = Flatten.readAll(stack.lore(), stack.loreComponents());
        return lines.isEmpty() ? "" : "\n    " + String.join("\n    ", lines);
    }

    /** A model worth naming: one that is not simply the item's own id. */
    static String model(String name, String itemModel) {
        return itemModel == null || itemModel.equals("minecraft:" + name) ? null : itemModel;
    }
}
