package kr.junhyung.mcagents.render;

/**
 * What went on the ground, including the case where nothing did.
 *
 * <p>An empty cursor is a state and not a refusal, so it travels as {@code dropped: null} and this
 * writes the sentence for it.
 */
public final class DroppedItemRenderer implements Renderer<DroppedItemRenderer.View> {

    public record View(Integer slot, Held dropped) {}

    @Override
    public String render(View view) {
        String from = view.slot() == null ? "the cursor" : "slot " + view.slot();

        if (view.dropped() == null) {
            return capitalised(from) + " was empty, so there was nothing to drop.";
        }

        return "Dropped " + Held.describe(view.dropped()) + " from " + from + ".";
    }

    private static String capitalised(String from) {
        return Character.toUpperCase(from.charAt(0)) + from.substring(1);
    }
}
