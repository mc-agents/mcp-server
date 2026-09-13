package kr.junhyung.mcagents.render;

import java.util.List;
import java.util.stream.Collectors;

public final class StatsRenderer implements Renderer<StatsRenderer.View> {

    private static final String GENERAL = "minecraft:custom";

    public record Stat(String type, String target, int value, String formatted) {}

    public record OtherType(String type, int nonZero) {}

    public record View(String type, String target, int matched, List<Stat> stats, List<OtherType> otherTypes) {}

    @Override
    public String render(View view) {
        if (view.type() != null && view.target() != null && view.stats().size() == 1) {
            return "Statistic " + line(view.stats().getFirst(), true).strip();
        }

        String scope = view.target() != null
            ? "for " + view.target()
            : "in " + (view.type() == null ? GENERAL : view.type());
        String listing = view.stats().isEmpty()
            ? "No statistics " + scope + " are nonzero."
            : header(view, scope) + "\n" + view.stats().stream()
                .map(stat -> line(stat, view.type() == null && view.target() != null))
                .collect(Collectors.joining("\n"));

        if (view.otherTypes().isEmpty()) {
            return listing;
        }
        /*
        The kills per mob and the blocks mined are where a quest is counted, and none of them is in
        the general list. Naming the types that have something in them is what tells a caller to ask.
        */
        return listing + "\nNonzero in other types, listed by passing type: " + view.otherTypes().stream()
            .map(other -> other.type() + " (" + other.nonZero() + ")")
            .collect(Collectors.joining(", "));
    }

    private static String header(View view, String scope) {
        int shown = view.stats().size();
        /* Saying how many were left out is what tells a caller that count, not the server, ended the list. */
        String cut = shown < view.matched() ? ", the " + shown + " highest" : "";

        return view.matched() + " nonzero " + (view.matched() == 1 ? "statistic " : "statistics ") + scope + cut + ":";
    }

    /**
     * The raw number first, because it is what a statistic scoreboard objective holds and what a
     * server's quest compares against; the unit beside it is for reading "152340" as 1.52 km.
     */
    private static String line(Stat stat, boolean withType) {
        return "  " + (withType ? stat.type() + " " : "") + stat.target() + ": " + stat.value()
            + (stat.formatted() == null ? "" : " (" + stat.formatted() + ")");
    }
}
