package kr.junhyung.mcagents.render;

import java.util.List;

public final class BlockEntityRenderer implements Renderer<BlockEntityRenderer.View> {

    public record SignFace(String face, List<String> lines) {}

    public record View(String block, Point position, boolean present, List<SignFace> signFaces, String raw) {}

    @Override
    public String render(View view) {
        String where = view.block() + " at " + view.position();

        if (!view.present()) {
            return where + " carries no block entity data.";
        }

        String header = where + " " + Text.DATA_NOTICE + ":";

        if (!view.signFaces().isEmpty()) {
            return Text.withLines(header, view.signFaces().stream().map(BlockEntityRenderer::face).toList());
        }

        return header + "\n" + view.raw();
    }

    /**
     * A blank line on a sign is information: the lines below it sit where the server put them.
     */
    private static String face(SignFace side) {
        List<String> lines = side.lines().stream()
            .map(line -> line.isEmpty() ? "(blank)" : line)
            .toList();

        return "  " + side.face() + ": " + String.join(" / ", lines);
    }
}
