package kr.junhyung.mcagents.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The two arguments read-window takes that never reach the bot. The whole window crosses the
 * wire; which half is shown, and whether lore comes with it, is settled here.
 */
class WindowRendererTest {

    private static final String HEADER =
            "window \"Shop\" (type minecraft:generic_9x3, 63 slots, container 0-26, player inventory 27-62)";

    private final WindowRenderer renderer = new WindowRenderer();

    private final WindowRenderer.View shop = new WindowRenderer.View("Shop", "minecraft:generic_9x3", 63,
            List.of(0, 26), List.of(27, 62),
            List.of(
                    new WindowRenderer.Slot(11, "paper", 1, "Mana Potion", List.of("Restores 50 mana"), null, null),
                    new WindowRenderer.Slot(12, "paper", 1, "Mana Potion", List.of("Restores 80 mana"), null, null),
                    new WindowRenderer.Slot(40, "bread", 3, null, List.of(), null, null)),
            null);

    @Test
    void theContainerHalfAloneLeavesThePlayersInventoryOut() {
        assertEquals(HEADER + "\n  11: Mana Potion [paper] x1\n    Restores 50 mana\n  12: Mana Potion [paper] x1\n    Restores 80 mana",
                renderer.render(shop, Map.of("part", "container")));
        assertEquals(HEADER + "\n  40: bread x3", renderer.render(shop, Map.of("part", "inventory")));
    }

    @Test
    void aHalfWithNothingInItSaysSoWithoutClaimingTheWholeWindowIsEmpty() {
        WindowRenderer.View onlyInventory = new WindowRenderer.View("Shop", "minecraft:generic_9x3", 63,
                List.of(0, 26), List.of(27, 62),
                List.of(new WindowRenderer.Slot(40, "bread", 3, null, List.of(), null, null)), null);

        assertEquals(HEADER + "\nevery slot in the container half is empty",
                renderer.render(onlyInventory, Map.of("part", "container")));
    }

    /** Without lore two potions that differ only in their lore read the same, and are folded like the panes are. */
    @Test
    void withoutLoreTheListingIsNamesAndCountsOnly() {
        assertEquals(HEADER + "\n  slots 11-12: Mana Potion [paper] x1\n  40: bread x3",
                renderer.render(shop, Map.of("lore", false)));
    }
}
