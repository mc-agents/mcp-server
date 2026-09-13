package kr.junhyung.mcagents.render;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * What a menu offers to press, as the names press-container-button takes.
 *
 * <p>The button number is printed beside each one but the name leads, because the number is only
 * the menu's own reading of it: 0 is the cheapest enchantment on a table and whichever result
 * happens to come first on a stonecutter, and a caller that learns to press 0 learns nothing that
 * carries over.
 *
 * <p>A beacon's effects are listed apart from the buttons, for set-beacon-effects rather than
 * press-container-button.
 */
public final class ContainerOptionsRenderer implements Renderer<ContainerOptionsRenderer.View> {

    private static final String CARTOGRAPHY_TABLE = "minecraft:cartography_table";
    private static final String LECTERN = "minecraft:lectern";

    public record Option(int button, String name, String label, Integer count, Integer levels, Integer lapis,
                         boolean available, boolean selected) {}

    public record Effect(String name, String label, String slot, int levels, boolean available, boolean selected) {}

    public record Beacon(int levels, String payment, List<Effect> effects) {}

    public record Window(String title, JsonNode titleComponent, String type, List<Option> options,
                         Integer page, Integer pageCount, Beacon beacon) {}

    public record View(Window window) {}

    @Override
    public String render(View view) {
        Window window = view.window();

        if (window == null) {
            return "No window is open. Run whatever opens the menu first, then wait-for-window.";
        }

        String named = "window \"" + Flatten.read(window.title(), window.titleComponent()) + "\" (type "
            + window.type() + ")";

        if (window.beacon() != null) {
            return beacon(named, window.beacon());
        }
        if (window.options().isEmpty()) {
            return named + nothing(window.type());
        }

        String header = named + " offers " + window.options().size()
            + (window.options().size() == 1 ? " option" : " options");

        if (LECTERN.equals(window.type())) {
            header += ", open at page " + window.page() + " of " + window.pageCount();
        }

        List<String> lines = new ArrayList<>();

        for (Option option : window.options()) {
            lines.add(option(option));
        }
        if (LECTERN.equals(window.type()) && window.pageCount() != null && window.pageCount() > 0) {
            lines.add("  and \"page 1\" to \"page " + window.pageCount() + "\" to open the book at that page");
        }

        return Text.withLines(header + " " + Text.DATA_NOTICE + ":", lines);
    }

    /**
     * A menu with nothing to press is most menus, and one of them is worth explaining: the
     * cartography table looks like a stonecutter and is not one. Its result is made from the two
     * inputs alone, so there is never anything to choose.
     */
    private static String nothing(String type) {
        if (CARTOGRAPHY_TABLE.equals(type)) {
            return " has nothing to press: its result appears in slot 2 once both inputs are in, and click-slot takes it.";
        }
        return " has nothing to press right now, only slots.";
    }

    /**
     * A beacon's effects are not pressed one at a time, so they are not numbered like buttons. The
     * pyramid and the payment lead, because those are what refuse a choice, and a caller who reads
     * the list without them picks an effect that cannot be set.
     */
    private static String beacon(String named, Beacon beacon) {
        String pyramid = beacon.levels() == 0
            ? "no pyramid"
            : "a pyramid of " + levels(beacon.levels());
        String payment = beacon.payment() == null
            ? "nothing in the payment slot to pay with"
            : beacon.payment() + " in the payment slot";

        List<String> lines = new ArrayList<>();

        for (Effect effect : beacon.effects()) {
            List<String> notes = new ArrayList<>(List.of(effect.slot(), levels(effect.levels())));

            if (effect.selected()) {
                notes.add("selected");
            }
            if (!effect.available()) {
                notes.add("unavailable");
            }
            lines.add("  " + effect.label() + " [" + effect.name() + "], " + String.join(", ", notes));
        }

        return Text.withLines(named + " is a beacon on " + pyramid + ", with " + payment + " "
            + Text.DATA_NOTICE + ":", lines);
    }

    private static String levels(int levels) {
        return levels + (levels == 1 ? " level" : " levels");
    }

    private static String option(Option option) {
        List<String> notes = new ArrayList<>();

        if (option.count() != null) {
            notes.add("x" + option.count());
        }
        if (option.levels() != null) {
            notes.add(option.levels() + (option.levels() == 1 ? " level" : " levels") + " and "
                + option.lapis() + " lapis");
        }
        if (option.selected()) {
            notes.add("selected");
        }
        if (!option.available()) {
            notes.add("unavailable");
        }

        String line = "  " + option.button() + ". " + named(option);

        return notes.isEmpty() ? line : line + ", " + String.join(", ", notes);
    }

    /**
     * An enchanting table shows which enchantment an offer is only as a clue, and a server can
     * leave that clue out. The offer can still be pressed, by its number.
     */
    private static String named(Option option) {
        if (option.name() == null) {
            return "an offer the table does not name";
        }
        if (option.label() == null) {
            return option.name();
        }
        return option.label() + " [" + option.name() + "]";
    }
}
