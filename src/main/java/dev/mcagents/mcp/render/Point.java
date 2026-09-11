package dev.mcagents.mcp.render;

public record Point(int x, int y, int z) {

    @Override
    public String toString() {
        return Text.block(x, y, z);
    }
}
