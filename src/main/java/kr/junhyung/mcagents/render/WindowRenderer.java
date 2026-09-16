package kr.junhyung.mcagents.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /** One line of the listing: a slot, or a run of slots that read the same. */
    private record Line(int first, int last, String named, int count, List<String> lore) {

        boolean continues(Slot slot, String named, List<String> lore) {
            return slot.slot() == last + 1 && lore.isEmpty() && this.lore.isEmpty()
                && this.named.equals(named) && this.count == slot.count();
        }
    }

    @Override
    public String render(View view) {
        return render(view, Map.of());
    }

    /**
     * {@code part} and {@code lore} never reach the bot: the whole window crosses the wire and the
     * trimming happens here, so the two kinds cannot trim differently. A plugin menu is a page of
     * panes above the player's own thirty-six slots, re-read after every click, and the container
     * half without lore is usually the whole of what the click changed.
     */
    @Override
    public String render(View view, Map<String, Object> arguments) {
        String part = arguments.get("part") instanceof String given ? given : "all";
        boolean withLore = !Boolean.FALSE.equals(arguments.get("lore"));

        String header = "window \"" + Flatten.read(view.title(), view.titleComponent()) + "\" (type " + type(view.type()) + ", "
            + view.slotCount() + " slots, "
            + "container " + view.containerSlots().get(0) + "-" + view.containerSlots().get(1) + ", "
            + "player inventory " + view.inventorySlots().get(0) + "-" + view.inventorySlots().get(1) + ")";

        List<Slot> shown = view.filled().stream().filter(slot -> within(slot, part, view)).toList();

        if (shown.isEmpty()) {
            return header + (view.filled().isEmpty() ? "\nevery slot is empty"
                : "\nevery slot in the " + part + " half is empty");
        }

        return Text.withLines(header, lines(shown, withLore).stream().map(WindowRenderer::line).toList());
    }

    private static boolean within(Slot slot, String part, View view) {
        List<Integer> range = switch (part) {
            case "container" -> view.containerSlots();
            case "inventory" -> view.inventorySlots();
            default -> null;
        };
        return range == null || slot.slot() >= range.get(0) && slot.slot() <= range.get(1);
    }

    /**
     * Consecutive slots holding the same stack collapse into one line. A plugin fills the empty
     * slots of a menu with panes, and forty-five lines of the same pane say nothing the one line
     * "slots 0-44: black_stained_glass_pane x1" does not. A stack with lore is never folded: the
     * lore is what tells two same-named items apart.
     */
    private static List<Line> lines(List<Slot> slots, boolean withLore) {
        List<Line> lines = new ArrayList<>();

        for (Slot slot : slots) {
            String named = named(slot);
            List<String> lore = withLore ? Flatten.readAll(slot.lore(), slot.loreComponents()) : List.of();
            Line last = lines.isEmpty() ? null : lines.getLast();

            if (last != null && last.continues(slot, named, lore)) {
                lines.set(lines.size() - 1, new Line(last.first(), slot.slot(), named, slot.count(), lore));
            } else {
                lines.add(new Line(slot.slot(), slot.slot(), named, slot.count(), lore));
            }
        }
        return lines;
    }

    private static String named(Slot slot) {
        return slot.label() == null
            ? slot.name()
            : Flatten.read(slot.label(), slot.labelComponent()) + " [" + slot.name() + "]";
    }

    private static String line(Line line) {
        String where = line.first() == line.last()
            ? String.valueOf(line.first())
            : "slots " + line.first() + "-" + line.last();
        String lore = line.lore().isEmpty() ? "" : "\n    " + String.join("\n    ", line.lore());

        return "  " + where + ": " + line.named() + " x" + line.count() + lore;
    }

    private static String type(Object type) {
        return type instanceof Number number ? Text.number(number.doubleValue()) : String.valueOf(type);
    }
}
