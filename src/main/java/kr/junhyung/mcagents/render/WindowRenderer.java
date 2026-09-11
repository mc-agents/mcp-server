package kr.junhyung.mcagents.render;

import java.util.List;

public final class WindowRenderer implements Renderer<WindowRenderer.View> {

    public record Slot(int slot, String name, int count, String label, List<String> lore) {}

    /**
     * {@code type} is a registry id on a modern server and a number on an older one, and the bot passes on
     * whichever it was given rather than inventing one the other kind of bot would disagree about.
     */
    public record View(String title, Object type, int slotCount, List<Integer> containerSlots,
        List<Integer> inventorySlots, List<Slot> filled) {}

    @Override
    public String render(View view) {
        String header = "window \"" + view.title() + "\" (type " + type(view.type()) + ", "
            + view.slotCount() + " slots, "
            + "container " + view.containerSlots().get(0) + "-" + view.containerSlots().get(1) + ", "
            + "player inventory " + view.inventorySlots().get(0) + "-" + view.inventorySlots().get(1) + ")";

        if (view.filled().isEmpty()) {
            return header + "\nevery slot is empty";
        }

        return Text.withLines(header, view.filled().stream().map(WindowRenderer::slot).toList());
    }

    private static String slot(Slot slot) {
        String label = slot.label() == null ? slot.name() : slot.label() + " [" + slot.name() + "]";
        String lore = slot.lore().isEmpty() ? "" : "\n    " + String.join("\n    ", slot.lore());

        return "  " + slot.slot() + ": " + label + " x" + slot.count() + lore;
    }

    private static String type(Object type) {
        return type instanceof Number number ? Text.number(number.doubleValue()) : String.valueOf(type);
    }
}
