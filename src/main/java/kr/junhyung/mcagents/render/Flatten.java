package kr.junhyung.mcagents.render;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The one place a component is broken into the pieces a renderer joins.
 *
 * <p>It used to be two places -- once in Java inside the fabric bot, once in TypeScript inside the
 * mineflayer one -- and every divergence between the two showed up as two bots describing one HUD
 * differently. Three bugs in a single day came from the TypeScript copy: the 26.x
 * <code>{"": "x"}</code> shorthand it could not read, translate keys it left as keys, and a
 * prismarine wrapper whose style it never looked inside. None came from the Java copy, because that
 * one asks the game. A bot that can send the component it was given no longer has to be right about
 * any of this.
 *
 * <p>What a bot still does better is a translate key: it has the game's language table and this does
 * not. So a component with one anywhere in it is left to the pieces the bot sent.
 */
public final class Flatten {

    /** The private use area, where a resource pack puts the glyphs it draws a HUD out of. */
    private static final Pattern GLYPHS =
        Pattern.compile("[\\uE000-\\uF8FF]|[\\uDB80-\\uDBBF][\\uDC00-\\uDFFF]");

    private static final Pattern COLOUR_CODES = Pattern.compile("§[0-9a-fk-or]", Pattern.CASE_INSENSITIVE);

    private Flatten() {}

    /**
     * The readable pieces of a component, or null when this cannot be the one to say.
     *
     * <p>Null rather than empty: a component that is genuinely empty and a component this cannot
     * read are different answers, and the caller falls back to the bot's own pieces for the second.
     */
    public static List<Piece> pieces(JsonNode component) {
        if (component == null || component.isNull() || component.isMissingNode()) {
            return null;
        }
        if (translates(component)) {
            return null;
        }

        List<Piece> pieces = new ArrayList<>();
        walk(component, null, null, pieces, false);

        return pieces;
    }

    /**
     * A line a server wrote, read from its component when there is one and from the bot's own
     * flattening when there is not.
     *
     * <p>The two travel side by side for every label: a bot that cannot send a component -- or one
     * whose component holds a translate key only the game can resolve -- still says what it read.
     */
    public static String read(String text, JsonNode component) {
        return Piece.join(pieces(component), text == null ? "" : text);
    }

    /**
     * The same, for a list. Paired by position, because that is how the two arrive: a bot sends the
     * lines it read and the components it read them from, in the order the item carries them. A
     * shorter list of components is not a disagreement worth refusing over -- the line is read the
     * way it would have been before there were any.
     */
    public static List<String> readAll(List<String> lines, List<JsonNode> components) {
        if (lines == null) {
            return List.of();
        }

        List<String> read = new ArrayList<>(lines.size());

        for (int index = 0; index < lines.size(); index++) {
            JsonNode component = components == null || index >= components.size()
                ? null
                : components.get(index);
            read.add(read(lines.get(index), component));
        }

        return read;
    }

    /**
     * How many pieces held nothing but glyphs. On a HUD those are spacers; on a display entity a
     * piece made only of them is an icon, which is not the same as an empty display.
     */
    public static Integer glyphPieces(JsonNode component) {
        if (component == null || component.isNull() || component.isMissingNode() || translates(component)) {
            return null;
        }

        List<Piece> glyphs = new ArrayList<>();
        walk(component, null, null, glyphs, true);

        return glyphs.size();
    }

    private static void walk(JsonNode node, String font, String colour, List<Piece> into, boolean glyphsOnly) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                walk(child, font, colour, into, glyphsOnly);
            }
            return;
        }
        if (node.isString()) {
            take(node.stringValue(), font, colour, into, glyphsOnly);
            return;
        }
        if (!node.isObject()) {
            take(node.asString(), font, colour, into, glyphsOnly);
            return;
        }

        /* An NBT value arrives as {type, value}: prismarine passes those straight through. */
        if (node.has("type") && node.has("value") && node.get("type").isString()) {
            walk(node.get("value"), font, colour, into, glyphsOnly);
            return;
        }

        String ownFont = text(node, "font", font);
        String ownColour = text(node, "colour", text(node, "color", colour));

        /* 26.x writes a component whose only field is its text with the empty string as the key. */
        JsonNode own = node.has("text") ? node.get("text") : node.get("");
        if (own != null) {
            walk(own, ownFont, ownColour, into, glyphsOnly);
        }

        walk(node.get("extra"), ownFont, ownColour, into, glyphsOnly);
    }

    /**
     * One leaf. Not trimmed: "Mana " and "Mana" are different pieces, and a server that writes a
     * label and a number as two components puts the space in one of them.
     */
    private static void take(String raw, String font, String colour, List<Piece> into, boolean glyphsOnly) {
        if (raw == null || raw.isEmpty()) {
            return;
        }

        String readable = COLOUR_CODES.matcher(GLYPHS.matcher(raw).replaceAll("")).replaceAll("");
        boolean onlyGlyphs = !raw.isBlank() && readable.isBlank();

        if (glyphsOnly) {
            if (onlyGlyphs) {
                into.add(new Piece(raw, font, colour));
            }
            return;
        }
        if (!readable.isBlank()) {
            into.add(new Piece(readable, font, colour));
        }
    }

    /**
     * A style field, which is a plain string in JSON and a {@code {type, value}} wrapper in NBT. A
     * component that came through prismarine is the second, and reading only the first dropped the
     * font off every nameplate while keeping it on every boss bar -- the same server, two shapes.
     */
    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);

        if (value != null && value.isObject() && value.has("value")) {
            value = value.get("value");
        }
        if (value == null || !value.isString()) {
            return fallback;
        }
        return value.stringValue();
    }

    /**
     * Whether a translate key is anywhere in here. The bot that sent this has the game's language
     * table and resolved it; this does not, and reporting the key where a window plainly says
     * "Chest" is the mistake the other kind of bot used to make.
     */
    private static boolean translates(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isArray() || node.isObject()) {
            if (node.has("translate")) {
                return true;
            }
            for (JsonNode child : node) {
                if (translates(child)) {
                    return true;
                }
            }
        }
        return false;
    }
}
