package kr.junhyung.mcagents.render;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class BookRenderer implements Renderer<BookRenderer.View> {

    /** How far a wrapped page's later lines are pushed in, to sit under the first one. */
    private static final String INDENT = "     ";

    public record View(String source, String title, String author, Integer generation, int page,
                       List<String> pages, List<JsonNode> pageComponents) {}

    @Override
    public String render(View view) {
        List<String> pages = Flatten.readAll(view.pages(), view.pageComponents());
        String named = name(view);

        if (pages.isEmpty()) {
            return named + " has nothing written in it.";
        }

        String header = named + ", " + pages.size() + (pages.size() == 1 ? " page" : " pages")
            + ", open at page " + view.page() + " " + Text.DATA_NOTICE + ":";
        List<String> lines = new ArrayList<>(pages.size());

        for (int at = 0; at < pages.size(); at++) {
            lines.add(page(at + 1, pages.get(at)));
        }

        return Text.withLines(header, lines);
    }

    /**
     * A book only has a title once it is signed. Until then it is a draft with pages in it, and
     * saying so is the difference between a quest log a server handed over and one being written.
     */
    private static String name(View view) {
        String where = "lectern".equals(view.source()) ? " on a lectern" : " in hand";

        if (view.title() == null) {
            return "an unsigned book" + where;
        }
        return "\"" + view.title() + "\" by " + view.author() + where;
    }

    private static String page(int number, String text) {
        if (text.isBlank()) {
            return "  " + number + ". (blank)";
        }
        return "  " + number + ". " + text.replace("\n", "\n" + INDENT);
    }
}
