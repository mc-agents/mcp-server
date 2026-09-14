package kr.junhyung.mcagents.render;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * A dialog, in the words a reader gets.
 *
 * <p>A dialog is not one piece of text, so it cannot travel as a component the way a boss bar's
 * title does: it is a title, some lines of body, a row of buttons and its inputs, and
 * which of those to read and in what order is presentation. So a bot sends the dialog as the game
 * serialises it and this reads it -- the same bargain as a component, and for the same reason. It
 * used to be read inside one kind of bot, which had a rule the other kind could only duplicate, and
 * the other kind had no dialog feed at all.
 *
 * <p>Every piece carries its own component, because a server draws a dialog the way it draws a HUD:
 * the title of a real one is a glyph and a label in the pack's own fonts.
 */
public final class Dialogs {

    /** The feed these lines belong to. */
    public static final String FEED = "dialog";

    /** A line the bot sends when the dialog goes away, which carries no dialog to describe. */
    private static final String CLOSED = "closed";

    /**
     * Where each kind of dialog keeps its buttons. {@code actions} belongs to multi_action,
     * {@code yes} and {@code no} to confirmation, {@code action} to notice, and every list-shaped
     * one can carry an {@code exit_action}. Reading all of them means a caller is told what there
     * is to press whichever kind the server sent.
     */
    private static final List<String> BUTTONS = List.of("actions", "yes", "no", "action", "exit_action");

    private Dialogs() {}

    /**
     * One line of the dialog feed.
     *
     * <p>The fallback is the bot's own text, for a bot that sent no dialog: a third kind written
     * against the protocol document would still be readable while it only sent a sentence.
     */
    public static String describe(String source, JsonNode dialog, String fallback) {
        if (CLOSED.equals(source)) {
            return "the dialog was closed";
        }
        if (dialog == null || !dialog.isObject()) {
            return fallback;
        }

        List<String> parts = new ArrayList<>();
        String title = Flatten.read("", dialog.get("title"));

        if (!title.isEmpty()) {
            parts.add(title);
        }

        List<String> body = new ArrayList<>();
        for (JsonNode line : each(dialog.get("body"))) {
            /* An item body has a stack and no words; the rest carry their text under contents. */
            add(body, Flatten.read("", line.get("contents")));
        }
        if (!body.isEmpty()) {
            parts.add(String.join(" / ", body));
        }

        List<String> buttons = new ArrayList<>();
        for (String field : BUTTONS) {
            for (JsonNode button : each(dialog.get(field))) {
                add(buttons, Flatten.read("", button.get("label")));
            }
        }
        if (!buttons.isEmpty()) {
            parts.add("buttons: " + String.join(", ", buttons));
        }

        List<String> inputs = new ArrayList<>();
        for (JsonNode input : each(dialog.get("inputs"))) {
            inputs.add(input(input, dialog.path("values")));
        }
        if (!inputs.isEmpty()) {
            parts.add("inputs: " + String.join(", ", inputs));
        }

        return parts.isEmpty() ? fallback : String.join(" | ", parts);
    }

    /**
     * One input as {@code key (what it takes) = what it holds}: the key is what set-dialog-input and
     * the action's template name it by, and a label drawn in the pack's glyphs names nothing.
     *
     * <p>What it holds is the bot's {@code values} when it sent them, which it does once a value has
     * been set, and otherwise what the dialog starts it at -- the default a player would see too.
     */
    private static String input(JsonNode input, JsonNode values) {
        String key = input.path("key").asString("");
        String type = input.path("type").asString("").replaceFirst("^minecraft:", "");
        JsonNode held = values.get(key);

        return switch (type) {
            case "boolean" -> key + " (checkbox) = " + (held != null ? held.asString() : input.path("initial").asBoolean(false));
            case "single_option" -> option(key, input.get("options"), held);
            case "number_range" -> slider(key, input, held);
            case "text" -> key + " (text) = \"" + (held != null ? held.asString() : input.path("initial").asString("")) + "\"";
            default -> key + " (" + type + ")";
        };
    }

    /** An option is written as its id alone or as an object with one, and the first is chosen when none says it is. */
    private static String option(String key, JsonNode options, JsonNode held) {
        List<String> ids = new ArrayList<>();
        String initial = null;

        for (JsonNode option : each(options)) {
            String id = option.isString() ? option.asString() : option.path("id").asString("");
            ids.add(id);
            if (initial == null && option.path("initial").asBoolean(false)) {
                initial = id;
            }
        }
        String chosen = held != null ? held.asString() : initial != null ? initial : ids.isEmpty() ? "" : ids.getFirst();

        return key + " (one of " + String.join(", ", ids) + ") = " + chosen;
    }

    /** A slider with no starting value starts halfway, as the client draws it. */
    private static String slider(String key, JsonNode input, JsonNode held) {
        double start = input.path("start").asDouble(0);
        double end = input.path("end").asDouble(0);
        String step = input.has("step") ? ", step " + Text.number(input.path("step").asDouble()) : "";
        double value = held != null ? held.asDouble() : input.has("initial") ? input.path("initial").asDouble() : (start + end) / 2;

        return key + " (" + Text.number(start) + " to " + Text.number(end) + step + ") = " + Text.number(value);
    }

    /**
     * A field that holds either one of something or a list of them.
     *
     * <p>NBT writes a list of one as a bare compound, so a dialog with one line of body arrives
     * shaped differently from one with two, and the bot that reads the dialog out of NBT passes
     * that shape straight on.
     */
    private static List<JsonNode> each(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (!node.isArray()) {
            return List.of(node);
        }

        List<JsonNode> entries = new ArrayList<>();
        node.forEach(entries::add);

        return entries;
    }

    private static void add(List<String> lines, String line) {
        if (!line.isEmpty()) {
            lines.add(line);
        }
    }
}
