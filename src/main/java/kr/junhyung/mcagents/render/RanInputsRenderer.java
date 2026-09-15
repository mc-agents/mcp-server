package kr.junhyung.mcagents.render;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * What a sequence of inputs did, step by step, and where it stopped.
 *
 * <p>Every step that ran is listed with the ticks it ran on, because the ticks are what the tool is
 * for: a caller asking for a click, twenty ticks, a click wants to read that the second click went
 * out twenty ticks after the first was answered. A press or a click says what press-input or
 * click-slot would have said for it, so the slot lines a QA run reads after a click are the same
 * lines whether the click was one call or one step.
 *
 * <p>The step the game refused, or the one timeoutMs ran out during, is named in the first line
 * with its reason, and the steps after it are named as not made: the steps before it are still
 * reported, because what they confirmed is why the sequence was asked for.
 */
public final class RanInputsRenderer implements Renderer<RanInputsRenderer.View> {

    private static final PressedInputRenderer PRESSED = new PressedInputRenderer();
    private static final ClickedSlotRenderer CLICKED = new ClickedSlotRenderer();

    /** Continuation lines of a delegated renderer sit under the step's own line. */
    private static final String CONTINUATION = "     ";

    public record WaitedFor(String feed, String pattern, String matched, int waitedMs) {}

    public record StepError(String code, String message) {}

    /**
     * {@code asked} is the step as the bot parsed it, set before it ran, so a step that never got to
     * answer is still named. Of press, click, command, wait and waitFor exactly the one the kind names
     * is set, and none when the step carries an error. The wire's {@code wait} is {@code waited}
     * here only because a record component cannot be called wait.
     */
    public record Step(String kind, String asked, int startedTick, int endedTick, PressedInputRenderer.View press,
        ClickedSlotRenderer.View click, String command, @JsonProperty("wait") Integer waited, WaitedFor waitFor,
        StepError error) {}

    public record View(int asked, int ran, int ticks, String stopped, List<Step> steps) {}

    @Override
    public String render(View view) {
        List<String> lines = new ArrayList<>();

        for (int index = 0; index < view.steps().size(); index++) {
            Step step = view.steps().get(index);
            if (step.error() != null) {
                continue;
            }
            String[] said = describe(step).split("\n");
            lines.add("  " + (index + 1) + ". " + ticks(step) + ": " + said[0]);
            for (int rest = 1; rest < said.length; rest++) {
                lines.add(CONTINUATION + said[rest]);
            }
        }
        return lines.isEmpty() ? header(view) : Text.withLines(header(view), lines);
    }

    /**
     * The step that stopped the sequence is named with the bot's own reason, whichever way it
     * stopped: the reason is what carries the budget that ran out, and the step's line is not in
     * the list below.
     */
    private static String header(View view) {
        String ran = "Ran " + view.ran() + " of " + view.asked() + " steps in " + ticks(view.ticks());
        Step failed = view.steps().isEmpty() ? null : view.steps().getLast();
        if (failed == null || failed.error() == null) {
            return ran + ".";
        }
        int number = view.steps().size();
        String reason = failed.error().code() + ": " + failed.error().message();

        if ("timeout".equals(view.stopped())) {
            return ran + "; step " + number + " (" + failed.asked() + ") was cut short -- " + reason
                + ("press".equals(failed.kind()) ? "; it was let go." : ".") + notMade(view, number);
        }
        return ran + "; step " + number + " (" + failed.asked() + ") was refused -- " + sentence(reason)
            + notMade(view, number);
    }

    /** The steps after the one that stopped the sequence, which were never started. */
    private static String notMade(View view, int stoppedAt) {
        int first = stoppedAt + 1;
        int last = view.asked();

        if (first > last) {
            return "";
        }
        return first == last
            ? " Step " + first + " was not made."
            : " Steps " + first + "-" + last + " were not made.";
    }

    private static String describe(Step step) {
        return switch (step.kind()) {
            case "press" -> PRESSED.render(step.press());
            case "click" -> CLICKED.render(step.click());
            case "command" -> "sent " + step.command();
            case "wait" -> "waited " + ticks(step.waited());
            case "waitFor" -> "the " + step.waitFor().feed() + " feed matched /" + step.waitFor().pattern() + "/ (\""
                + step.waitFor().matched() + "\") after " + step.waitFor().waitedMs() + "ms";
            default -> throw new IllegalArgumentException("unknown kind " + step.kind());
        };
    }

    private static String ticks(Step step) {
        return step.startedTick() == step.endedTick()
            ? "tick " + step.startedTick()
            : "ticks " + step.startedTick() + "-" + step.endedTick();
    }

    private static String ticks(int count) {
        return count + (count == 1 ? " tick" : " ticks");
    }

    private static String sentence(String message) {
        return message.endsWith(".") ? message : message + ".";
    }
}
