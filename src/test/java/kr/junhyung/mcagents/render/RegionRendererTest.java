package kr.junhyung.mcagents.render;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the legend carries, which is what decides whether a box is drawn as well as counted.
 */
class RegionRendererTest {

    private final RegionRenderer renderer = new RegionRenderer();

    /**
     * Air is drawn as a dot and spends no letter, so a palette of thirty-seven with air among them
     * spends thirty-six, which is exactly what the legend holds. Measuring the palette instead of
     * what it spends refused that box a map it could have had.
     */
    @Test
    void aPaletteIsMeasuredByTheLettersItSpendsAndAirSpendsNone() {
        String rendered = renderer.render(row(36));

        assertTrue(rendered.contains("One character a block"), rendered);
        assertTrue(rendered.endsWith("\n    .abcdefghijklmnopqrstuvwxyz0123456789"), rendered);
    }

    /** One letter past what it holds, and the number in the refusal is the one the decision used. */
    @Test
    void aPaletteThatSpendsMoreLettersThanThereAreSaysHowManyItNeeded() {
        String rendered = renderer.render(row(37));

        assertTrue(rendered.contains("No map: 37 kinds of block need a letter each, and the legend has 36."),
                rendered);
    }

    /**
     * A row of one block of each kind, air first. The ids stand in for real ones: what is under
     * test is how many of them there are, and the renderer reads nothing else about a name except
     * whether it is air.
     */
    private static RegionRenderer.View row(int kinds) {
        List<String> palette = new ArrayList<>();
        List<RegionRenderer.View.Run> runs = new ArrayList<>();

        palette.add("air");
        for (int kind = 0; kind < kinds; kind++) {
            palette.add("block" + kind);
        }
        for (int entry = 0; entry < palette.size(); entry++) {
            runs.add(new RegionRenderer.View.Run(entry, 1));
        }
        return new RegionRenderer.View(new Point(0, 64, 0), new Point(kinds, 64, 0),
                new RegionRenderer.View.Size(palette.size(), 1, 1), palette.size(), palette, runs, 0, 0);
    }
}
