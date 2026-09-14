package kr.junhyung.mcagents.render;

import tools.jackson.databind.JsonNode;

/**
 * What a dialog's checkbox, cycle or slider holds after it was set.
 *
 * <p>The value and the one before it both travel, because a caller who set a slider to 5 and got 6
 * has to be told the slider moved it: the button sends what the control holds, not what was asked.
 */
public final class DialogInputRenderer implements Renderer<DialogInputRenderer.View> {

    public record View(String key, String type, JsonNode value, JsonNode previous, String display, Double requested) {}

    @Override
    public String render(View view) {
        StringBuilder text = new StringBuilder("Set \"").append(view.key()).append("\" to ").append(value(view.value()));

        if (view.display() != null && !view.display().equals(view.value().asString(""))) {
            text.append(" (shown as \"").append(view.display()).append("\")");
        }
        text.append(", was ").append(value(view.previous())).append('.');

        if (view.requested() != null) {
            text.append(' ').append(Text.number(view.requested()))
                .append(" is not one of the slider's steps, so it moved to the nearest.");
        }
        return text.toString();
    }

    static String value(JsonNode value) {
        if (value == null || value.isNull()) {
            return "nothing";
        }
        return value.isNumber() ? Text.number(value.asDouble()) : value.asString();
    }
}
