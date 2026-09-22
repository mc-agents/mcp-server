package kr.junhyung.mcagents.render;

import java.util.List;
import java.util.Optional;

/**
 * What WorldEdit has selected, as the plugin described it over its own channel.
 *
 * <p>The corners are numbered rather than named on the wire, because that is how WorldEdit numbers
 * them; the sentence calls them pos1 and pos2, which is what the commands that set them are called
 * and what anybody reading the answer is about to type.
 */
public final class SelectionRenderer implements Renderer<SelectionRenderer.View> {

    public record Corner(int index, int x, int y, int z) {}

    public record View(boolean supported, String shape, List<Corner> points, Long volume) {}

    /** The selector WorldEdit's own commands set, and the only one whose corners come through as points. */
    private static final String CUBOID = "cuboid";

    @Override
    public String render(View view) {
        if (!view.supported()) {
            return "The server did not describe a selection. It sends nothing on WorldEdit's CUI channel,"
                + " so it has no WorldEdit or FastAsyncWorldEdit, its WorldEdit does not send CUI, or a proxy"
                + " in front of it drops the channel. What //pos1 says in chat is what is left to read.";
        }

        String shape = view.shape() == null ? CUBOID : view.shape();
        Optional<Corner> first = corner(view, 0);
        Optional<Corner> second = corner(view, 1);

        if (first.isEmpty() && second.isEmpty()) {
            return "Nothing is selected" + (shape.equals(CUBOID) ? "." : ", and the selector is a " + shape + ".");
        }
        if (first.isEmpty() || second.isEmpty()) {
            Corner set = first.orElseGet(second::orElseThrow);

            return "Only %s is set, at %s; the selection is not complete."
                .formatted(named(set.index()), at(set));
        }

        String span = "The selection is a %s from %s to %s".formatted(shape, at(first.get()), at(second.get()));

        return view.volume() == null ? span + "." : span + ": " + view.volume() + " blocks.";
    }

    private static Optional<Corner> corner(View view, int index) {
        return view.points() == null ? Optional.empty()
            : view.points().stream().filter(point -> point.index() == index).findFirst();
    }

    /** WorldEdit numbers the corners from zero; the commands that set them are numbered from one. */
    private static String named(int index) {
        return "pos" + (index + 1);
    }

    private static String at(Corner corner) {
        return Text.block(corner.x(), corner.y(), corner.z());
    }
}
