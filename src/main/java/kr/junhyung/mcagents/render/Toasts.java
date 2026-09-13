package kr.junhyung.mcagents.render;

import tools.jackson.databind.JsonNode;

/**
 * A toast, in the words a reader gets.
 *
 * <p>A feed rather than a reading tool, because a toast is on screen for five seconds and then
 * gone: a tool that read the corner of the screen would be asking whether the call happened to
 * land inside that window, and an agent's round trip is often longer than it. The bot sends one
 * line as each is put up, and this is what those lines say.
 *
 * <p>The id travels beside the title because the title is what a player sees and the id is what a
 * caller can name. A server that grants {@code myserver:quests/first_steps} when a quest is done
 * draws its title in the pack's own font, and a wait written against the id does not care.
 */
public final class Toasts {

    /** The feed these lines belong to. */
    public static final String FEED = "toast";

    private static final String ADVANCEMENT = "advancement";

    private static final String RECIPE = "recipe";

    private Toasts() {}

    public static String describe(String source, String text, JsonNode component, JsonNode data) {
        String title = Flatten.read(text, component);

        if (data == null || !data.isObject()) {
            return title;
        }
        if (ADVANCEMENT.equals(source)) {
            String description = Flatten.read(field(data, "description"), data.get("descriptionComponent"));
            String line = "advancement made: %s (%s, %s)"
                    .formatted(title, field(data, "id"), field(data, "frame"));

            return description.isEmpty() ? line : line + " | " + description;
        }
        if (RECIPE.equals(source)) {
            return "recipe unlocked: %s (%s)".formatted(title, field(data, "item"));
        }
        return title;
    }

    private static String field(JsonNode data, String name) {
        JsonNode value = data.get(name);

        return value == null || !value.isString() ? "" : value.stringValue();
    }
}
