package kr.junhyung.mcagents.render;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * One piece of a dialog's body a cursor can press or hover: a run of text with a click or hover
 * event on it, or an item.
 *
 * <p>A dialog that draws a whole screen in its body draws it in the resource pack's glyphs, and a
 * glyph means nothing as text. Read the way a HUD is read the glyphs would vanish, and a close mark
 * that is one glyph would be a piece with no name at all. So the glyphs are named by code point
 * where they stand: that is what tells one icon from the next, and what a resource pack maps them
 * by.
 */
public record DialogPiece(int index, String type, String text, JsonNode component, Box rect, Spot point,
    String blocked, Click click, Tooltip hover, Held item, Boolean showTooltip) {

    public record Box(int x, int y, int width, int height) {

        @Override
        public String toString() {
            return x + "," + y + " " + width + "x" + height;
        }
    }

    public record Spot(int x, int y) {

        @Override
        public String toString() {
            return x + "," + y;
        }
    }

    public record Click(String action, String value) {

        @Override
        public String toString() {
            return value.isEmpty() ? action : action + " " + value;
        }
    }

    public record Tooltip(List<String> lines, List<JsonNode> lineComponents) {}

    /** {@code #3 "Swap"}, or {@code #0 "<U+E101>" in font vellum} for a piece that is only glyphs. */
    public String name() {
        return "#" + index + " " + ("item".equals(type) ? "item " + Held.describe(item) : read(text, component));
    }

    /** One line of read-dialog-body: the piece, where it is, how it is reached, and what it does. */
    public String describe() {
        List<String> does = new ArrayList<>();
        if (click != null) {
            does.add("click: " + click);
        }
        if (hover != null) {
            List<String> lines = lines(hover);
            does.add(lines.isEmpty() ? "hover: no lines" : "hover: " + String.join(" / ", lines));
        }

        String reach = point == null ? "unreachable: " + blocked(blocked) : "reached at " + point;
        return name() + " covers " + rect + ", " + reach + (does.isEmpty() ? "" : "; " + String.join("; ", does));
    }

    /** A tooltip's lines, each read as a piece's text is. */
    public static List<String> lines(Tooltip tooltip) {
        List<String> read = new ArrayList<>();
        for (int i = 0; i < tooltip.lines().size(); i++) {
            List<JsonNode> components = tooltip.lineComponents();
            read.add(read(tooltip.lines().get(i), components != null && i < components.size() ? components.get(i) : null));
        }
        return read;
    }

    /**
     * Text as a reader is shown it. Without glyphs that is {@link Flatten}'s reading, font labels
     * and all. With them it is the text itself with each glyph named in place, because where a
     * glyph sits is what it means: a tooltip reading "<U+E010> 120  <U+E011> 3" prices something in
     * two currencies, and the same pieces with the icons taken out do not say which. A glyph
     * repeated as a spacer is named once with its count.
     */
    static String read(String text, JsonNode component) {
        if (!Flatten.GLYPHS.matcher(text).find()) {
            return "\"" + Flatten.read(text, component) + "\"";
        }

        StringBuilder named = new StringBuilder();
        int[] points = text.codePoints().toArray();
        for (int i = 0; i < points.length; i++) {
            String point = Character.toString(points[i]);
            if (!Flatten.GLYPHS.matcher(point).matches()) {
                named.append(point);
                continue;
            }
            int run = 1;
            while (i + run < points.length && points[i + run] == points[i]) {
                run++;
            }
            named.append("<U+%04X%s>".formatted(points[i], run > 1 ? " x" + run : ""));
            i += run - 1;
        }

        Set<String> fonts = new LinkedHashSet<>();
        fonts(component, fonts);
        return "\"" + named + "\"" + (fonts.isEmpty() ? "" : " in font " + String.join(", ", fonts));
    }

    private static void fonts(JsonNode node, Set<String> into) {
        if (node == null || !node.isContainer()) {
            return;
        }
        JsonNode font = node.get("font");
        if (font != null && font.isString()) {
            String name = font.stringValue();
            into.add(name.substring(name.indexOf(':') + 1));
        }
        for (JsonNode child : node) {
            fonts(child, into);
        }
    }

    static String blocked(String blocked) {
        return switch (blocked) {
            case "empty" -> "it draws nothing a cursor could rest on";
            case "scrolled" -> "it is scrolled out of the body's view";
            case "outside" -> "every glyph of it is drawn outside the widget that takes the press";
            case "covered" -> "a piece drawn after it takes every point of it";
            default -> blocked;
        };
    }
}
