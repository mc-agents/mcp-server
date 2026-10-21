package kr.junhyung.mcagents.render;

/** What the mouse did on a screen, and the screen that was open afterwards. */
public final class UsedMouseRenderer implements Renderer<UsedMouseRenderer.View> {

    public record View(DialogPiece.Spot point, String action, Integer scroll, String screenBefore, String screenAfter,
        HoveredSlotRenderer.Frame frame) {}

    @Override
    public String render(View view) {
        String did = switch (view.action()) {
            case "move" -> "Moved the mouse to " + view.point();
            case "left" -> "Pressed the left button at " + view.point();
            case "right" -> "Pressed the right button at " + view.point();
            case "scroll" -> "Turned the wheel " + Math.abs(view.scroll())
                + (Math.abs(view.scroll()) == 1 ? " notch " : " notches ")
                + (view.scroll() < 0 ? "down" : "up") + " at " + view.point();
            default -> throw new IllegalArgumentException("unknown action " + view.action());
        };

        String after;
        if (view.screenAfter() == null) {
            after = " No screen is open now.";
        } else if (view.screenAfter().equals(view.screenBefore())) {
            after = "";
        } else {
            after = " The screen is now " + view.screenAfter() + ".";
        }

        String frame = view.frame() == null
            ? ""
            : " The " + view.frame().width() + "x" + view.frame().height() + " frame is attached.";

        return did + " on " + view.screenBefore() + "." + after + frame;
    }
}
