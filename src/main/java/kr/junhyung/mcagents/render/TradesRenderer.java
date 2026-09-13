package kr.junhyung.mcagents.render;

import java.util.List;
import tools.jackson.databind.JsonNode;

public final class TradesRenderer implements Renderer<TradesRenderer.View> {

    public record View(String title, JsonNode titleComponent, int level, int xp, boolean showProgressBar,
        boolean canRestock, List<Trade> trades) {}

    @Override
    public String render(View view) {
        String named = merchant(view);

        /*
        The trades come in their own packet a moment after the screen, so an empty list straight
        after opening is a list that has not arrived. Saying the villager sells nothing would send an
        agent off to find another one.
        */
        if (view.trades().isEmpty()) {
            return named + " lists no trades yet. They arrive a moment after the screen opens: wait a few ticks and read-trades again.";
        }

        String header = named + ", " + view.trades().size() + (view.trades().size() == 1 ? " trade" : " trades")
            + (view.canRestock() ? "" : ", never restocks") + " " + Text.DATA_NOTICE + ":";

        return Text.withLines(header, view.trades().stream()
            .map(trade -> "  " + trade.number() + ". " + Trade.describe(trade))
            .toList());
    }

    /**
     * A wandering trader is sent a level as well, and the screen leaves it out because the progress
     * bar is off. Naming a level the player never sees would be reporting something that is not there.
     */
    private static String merchant(View view) {
        String title = "\"" + Flatten.read(view.title(), view.titleComponent()) + "\"";

        if (!view.showProgressBar()) {
            return title;
        }
        return title + " (level " + view.level() + ", " + view.xp() + " xp)";
    }
}
