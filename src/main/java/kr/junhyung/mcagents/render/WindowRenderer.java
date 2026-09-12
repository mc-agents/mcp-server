package kr.junhyung.mcagents.render;

import java.util.List;
import tools.jackson.databind.JsonNode;

public final class WindowRenderer implements Renderer<WindowRenderer.View> {

    /**
     * {@code labelComponent} and {@code loreComponents} are what the server actually wrote, beside
     * what the bot made of it. A menu built out of custom-named items is how a plugin draws a
     * screen, and the names carry the resource pack's own glyphs: flattened to a string the icons
     * vanish and the words run together, and the two kinds of bot lost different ones.
     */
    public record Slot(int slot, String name, int count, String label, List<String> lore,
        JsonNode labelComponent, List<JsonNode> loreComponents) {}

    /**
     * {@code type} is a registry id on a modern server and a number on an older one, and the bot passes on
     * whichever it was given rather than inventing one the other kind of bot would disagree about.
     */
    public record View(String title, Object type, int slotCount, List<Integer> containerSlots,
        List<Integer> inventorySlots, List<Slot> filled, JsonNode titleComponent) {}

    @Override
    public String render(View view) {
        String header = "window \"" + Flatten.read(view.title(), view.titleComponent()) + "\" (type " + type(view.type()) + ", "
            + view.slotCount() + " slots, "
            + "container " + view.containerSlots().get(0) + "-" + view.containerSlots().get(1) + ", "
            + "player inventory " + view.inventorySlots().get(0) + "-" + view.inventorySlots().get(1) + ")";

        if (view.filled().isEmpty()) {
            return header + "\nevery slot is empty";
        }

        return Text.withLines(header, view.filled().stream().map(WindowRenderer::slot).toList());
    }

    private static String slot(Slot slot) {
        String named = slot.label() == null
            ? slot.name()
            : Flatten.read(slot.label(), slot.labelComponent()) + " [" + slot.name() + "]";

        List<String> lines = Flatten.readAll(slot.lore(), slot.loreComponents());
        String lore = lines.isEmpty() ? "" : "\n    " + String.join("\n    ", lines);

        return "  " + slot.slot() + ": " + named + " x" + slot.count() + lore;
    }

    private static String type(Object type) {
        return type instanceof Number number ? Text.number(number.doubleValue()) : String.valueOf(type);
    }
}
