package kr.junhyung.mcagents.render;

import tools.jackson.databind.JsonNode;

/**
 * A press on a dialog's body, and what the screen became.
 *
 * <p>The outcome is the half that matters. A server answers a click by closing the dialog and
 * sending the next one, so "a dialog came in its place" is what a working press looks like, and "the
 * dialog is still up as it was" is the bug a player reports as "I clicked and nothing happened".
 */
public final class ClickedDialogBodyRenderer implements Renderer<ClickedDialogBodyRenderer.View> {

    public record Next(String title, JsonNode titleComponent) {}

    public record Confirmed(String button, String title) {}

    public record View(DialogPiece.Spot point, DialogPiece piece, String outcome, String screenAfter, Next dialogAfter,
        Confirmed confirmed) {}

    @Override
    public String render(View view) {
        DialogPiece piece = view.piece();
        String pressed = piece == null
            ? "Pressed " + view.point() + ", where nothing carries a click."
            : "Pressed " + piece.name() + " at " + view.point() + ", which sends " + piece.click() + ".";

        String confirmed = view.confirmed() == null
            ? ""
            : " The client asked to confirm it (" + view.confirmed().title() + ") and \""
                + view.confirmed().button() + "\" accepted.";

        String became = switch (view.outcome()) {
            case "replaced" -> " The server answered with dialog \""
                + Flatten.read(view.dialogAfter().title(), view.dialogAfter().titleComponent()) + "\" in its place.";
            case "closed" -> " The dialog closed, and nothing came in its place within two seconds.";
            case "unchanged" -> " Two seconds later the same dialog is still up: nothing on screen answered the press.";
            case "waiting" -> " Two seconds later the client is still waiting for the server to answer.";
            case "other" -> " The screen is now " + view.screenAfter() + ".";
            default -> throw new IllegalArgumentException("unknown outcome " + view.outcome());
        };
        return pressed + confirmed + became;
    }
}
