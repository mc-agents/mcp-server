package kr.junhyung.mcagents.render;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class AdvancementsRenderer implements Renderer<AdvancementsRenderer.View> {

    public record Advancement(String id, String title, JsonNode titleComponent, String description,
                              JsonNode descriptionComponent, String frame, boolean done, int criteriaDone,
                              int criteriaTotal, Long lastProgressAt) {}

    public record View(String prefix, String status, int matched, List<Advancement> advancements) {}

    @Override
    public String render(View view) {
        String scope = view.prefix() == null
            ? "outside the minecraft namespace"
            : "starting with \"" + view.prefix() + "\"";

        if (view.advancements().isEmpty()) {
            return switch (view.status()) {
                case "done" -> "No advancements " + scope + " are done.";
                case "inProgress" -> "No advancements " + scope + " are in progress.";
                default -> "No advancements " + scope + ".";
            };
        }

        String qualifier = switch (view.status()) {
            case "done" -> "done ";
            case "inProgress" -> "in-progress ";
            default -> "";
        };
        int shown = view.advancements().size();
        /* Saying how many were left out is what tells a caller that count, not the server, ended the list. */
        String cut = shown < view.matched() ? ", the " + shown + " most recent" : "";
        String header = view.matched() + " " + qualifier + (view.matched() == 1 ? "advancement " : "advancements ")
            + scope + cut + " " + Text.DATA_NOTICE + ":";

        return Text.withLines(header, view.advancements().stream().map(AdvancementsRenderer::line).toList());
    }

    /**
     * The id first, because it is what a caller types back and what a server's quest names. An
     * advancement with no display is still listed: a server uses one as a hidden trigger, and it
     * is often the one a check is about.
     */
    private static String line(Advancement advancement) {
        StringBuilder line = new StringBuilder("  ").append(advancement.id());

        if (advancement.title() != null) {
            line.append(" \"").append(Flatten.read(advancement.title(), advancement.titleComponent())).append('"');
        }
        if (advancement.frame() != null) {
            line.append(" (").append(advancement.frame()).append(')');
        }
        line.append(": ").append(progress(advancement));

        String description = advancement.description() == null
            ? ""
            : Flatten.read(advancement.description(), advancement.descriptionComponent());

        if (!description.isEmpty()) {
            line.append(". ").append(description);
        }
        return line.toString();
    }

    private static String progress(Advancement advancement) {
        if (advancement.done()) {
            return advancement.lastProgressAt() == null
                ? "done"
                : "done at " + Instant.ofEpochMilli(advancement.lastProgressAt());
        }
        return advancement.criteriaDone() + " of " + advancement.criteriaTotal() + " criteria";
    }
}
