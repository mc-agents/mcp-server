package kr.junhyung.mcagents.render;

import java.util.List;
import tools.jackson.databind.JsonNode;

public final class BlockEntityRenderer implements Renderer<BlockEntityRenderer.View> {

    public record SignFace(String face, List<String> lines, List<JsonNode> lineComponents) {}

    public record View(String block, Point position, boolean present, List<SignFace> signFaces, String raw) {}

    @Override
    public String render(View view) {
        String where = view.block() + " at " + view.position();

        if (!view.present()) {
            return where + " carries no block entity data.";
        }

        String header = where + " " + Text.DATA_NOTICE + ":";
        List<SignFace> faces = view.signFaces() == null ? List.of() : view.signFaces();

        if (!faces.isEmpty()) {
            return Text.withLines(header, faces.stream().map(BlockEntityRenderer::face).toList());
        }

        /*
        A block entity the bot has and cannot put into words. A Minecraft client is handed the
        decoded block entity rather than the server's tag, so there is nothing to write out for
        anything that is not a sign -- and saying so beats the "null" that used to be printed here.
        */
        if (view.raw() == null) {
            return where + " carries a block entity this bot cannot read.";
        }

        return header + "\n" + view.raw();
    }

    /**
     * A blank line on a sign is information: the lines below it sit where the server put them.
     */
    private static String face(SignFace side) {
        List<String> lines = Flatten.readAll(side.lines(), side.lineComponents()).stream()
            .map(line -> line.isEmpty() ? "(blank)" : line)
            .toList();

        return "  " + side.face() + ": " + String.join(" / ", lines);
    }
}
