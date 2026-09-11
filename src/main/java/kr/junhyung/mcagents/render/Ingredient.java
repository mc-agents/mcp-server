package kr.junhyung.mcagents.render;

public record Ingredient(String name, int count) {

    @Override
    public String toString() {
        return name + " x" + count;
    }
}
