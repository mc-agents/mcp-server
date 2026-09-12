package kr.junhyung.mcagents.render;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * A dialog, in the words a reader gets.
 *
 * <p>A dialog is not one piece of text, so it cannot travel as a component the way a boss bar's
 * title does: it is a title, some lines of body, a row of buttons and a count of input fields, and
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

        /* The values are not in the packet, so the count is the whole of what can be said. */
        int inputs = each(dialog.get("inputs")).size();
        if (inputs > 0) {
            parts.add(inputs + " input field(s)");
        }

        return parts.isEmpty() ? fallback : String.join(" | ", parts);
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
