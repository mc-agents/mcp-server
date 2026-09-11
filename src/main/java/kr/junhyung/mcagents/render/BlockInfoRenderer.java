package kr.junhyung.mcagents.render;

public final class BlockInfoRenderer implements Renderer<BlockInfoRenderer.View> {

    public record Block(String name, int type, Point position) {}

    public record View(Point position, Block block) {}

    @Override
    public String render(View view) {
        Block block = view.block();

        if (block == null) {
            return view.position() + " is outside the loaded chunks.";
        }

        return block.name() + " (type " + block.type() + ") at " + block.position() + ".";
    }
}
