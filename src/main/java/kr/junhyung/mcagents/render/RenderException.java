package kr.junhyung.mcagents.render;

public class RenderException extends RuntimeException {

    private final String tool;

    public RenderException(String tool, String message, Throwable cause) {
        super(message, cause);
        this.tool = tool;
    }

    public String tool() {
        return tool;
    }
}
