package kr.junhyung.mcagents.render;

/**
 * What was pressed, what it waited for and why it stopped.
 *
 * <p>Stopping on {@code until} and running out of presses are both an answer rather than a failure,
 * and telling them apart is the point: a caller repeating a click until a line shows needs to know
 * whether the line showed or the clicks just ran out.
 */
public final class PressedInputRenderer implements Renderer<PressedInputRenderer.View> {

    public record Watched(String feed, String pattern, String matched, Integer waitedMs) {}

    public record View(String key, Integer slot, int presses, int repeat, int holdTicks, int intervalTicks,
            Watched after, Watched until, String stopped, int selectedSlot) {}

    @Override
    public String render(View view) {
        StringBuilder said = new StringBuilder();

        if (view.after() != null) {
            said.append("Waited ").append(view.after().waitedMs()).append("ms for ").append(match(view.after(), "to match"))
                .append(", then ");
        }
        said.append(pressed(view));

        switch (view.stopped()) {
            case "until" -> said.append("; stopped at ").append(view.presses()).append(" of ").append(view.repeat())
                .append(" when ").append(match(view.until(), "matched"));
            case "timeout" -> said.append("; timeoutMs ran out after ").append(view.presses()).append(" of ")
                .append(view.repeat());
            default -> {
                if (view.until() != null) {
                    said.append("; the ").append(view.until().feed()).append(" feed never matched /")
                        .append(view.until().pattern()).append('/');
                }
            }
        }
        said.append('.');

        /* The selection is what a hotbar key or the wheel is for, and the server's is what the slot now holds. */
        if (view.key().equals("hotbar") || view.key().startsWith("scroll-")) {
            said.append(" Hotbar slot ").append(view.selectedSlot()).append(" is selected.");
        }
        String sentence = said.toString();
        return Character.toUpperCase(sentence.charAt(0)) + sentence.substring(1);
    }

    private static String pressed(View view) {
        String key = view.key().equals("hotbar") ? "hotbar slot " + view.slot() : view.key();

        if (view.presses() == 0) {
            return "did not press " + key;
        }
        StringBuilder said = new StringBuilder("pressed ").append(key).append(' ')
            .append(view.presses() == 1 ? "once" : view.presses() + " times");

        if (view.holdTicks() > 1) {
            said.append(view.presses() == 1 ? ", held for " : ", each held for ").append(ticks(view.holdTicks()));
        }
        if (view.presses() > 1) {
            said.append(", ").append(ticks(view.intervalTicks())).append(" apart");
        }
        return said.toString();
    }

    private static String match(Watched watched, String verb) {
        return "the " + watched.feed() + " feed " + verb + " /" + watched.pattern() + "/ (\"" + watched.matched() + "\")";
    }

    private static String ticks(int count) {
        return count + (count == 1 ? " tick" : " ticks");
    }
}
