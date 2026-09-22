package kr.junhyung.mcagents.tool;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.junhyung.mcagents.render.RegionRenderer;
import kr.junhyung.mcagents.schem.Nbt;
import org.junit.jupiter.api.Test;

/**
 * The file has to come back as the box that went in, and a file WorldEdit wrote has to come in
 * as the box WorldEdit meant. Both halves are the encoding, and the encoding is where a mistake
 * would put every block one place over and look fine on a floor.
 */
class SchematicTest {

    private static final Instant WHEN = Instant.parse("2026-09-22T00:00:00Z");

    private static RegionRenderer.View.Run run(int block, int count) {
        return new RegionRenderer.View.Run(block, count);
    }

    /** Written and read back: box, origin, name, palette with the namespace put back and taken off, every block. */
    @Test
    void aRegionSurvivesTheRoundTrip() throws IOException {
        Region box = new Region(10, 64, 20, 14, 66, 20);
        Snapshot kept = Snapshot.blank("r-0001", "arch", box, WHEN, "paper:25565")
                .with(box, List.of("stone", "air", "create:cogwheel", "oak_stairs[facing=north,half=top]"),
                        List.of(run(0, 1), run(1, 3), run(0, 2), run(2, 3), run(3, 6)));

        byte[] file = Schematic.write(kept, "26.1.2");
        Snapshot back = Schematic.read(file, "r-0002", null, 1_000, WHEN, "file");

        assertEquals(box, back.box());
        assertEquals("arch", back.name());
        assertEquals(kept.palette(), back.palette());
        assertArrayEquals(kept.raw(), back.raw());

        Nbt.Root root = Nbt.read(file);
        @SuppressWarnings("unchecked")
        Map<String, Object> schematic = (Map<String, Object>) root.compound().get("Schematic");
        @SuppressWarnings("unchecked")
        Map<String, Object> blocks = (Map<String, Object>) schematic.get("Blocks");
        @SuppressWarnings("unchecked")
        Map<String, Object> palette = (Map<String, Object>) blocks.get("Palette");

        assertEquals(3, schematic.get("Version"));
        assertEquals(4790, schematic.get("DataVersion"));
        assertEquals((short) 5, schematic.get("Width"));
        assertEquals(0, palette.get("minecraft:stone"));
        assertEquals(2, palette.get("create:cogwheel"));
        assertEquals(3, palette.get("minecraft:oak_stairs[facing=north,half=top]"));
    }

    /** A version 2 file, as WorldEdit 7.2 writes one: the table at the root, the data called BlockData. */
    @Test
    void aVersionTwoFileIsReadFromWhereItKeepsItsTable() throws IOException {
        Map<String, Object> palette = new LinkedHashMap<>();
        palette.put("minecraft:air", 0);
        palette.put("minecraft:stone", 1);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Version", 2);
        root.put("DataVersion", 4790);
        root.put("Width", (short) 2);
        root.put("Height", (short) 1);
        root.put("Length", (short) 2);
        root.put("Palette", palette);
        root.put("PaletteMax", 2);
        root.put("BlockData", new byte[] {1, 0, 0, 1});

        Snapshot back = Schematic.read(Nbt.write(new Nbt.Root("Schematic", root)), "r-0001", "given", 1_000, WHEN, "file");

        assertEquals(new Region(0, 0, 0, 1, 0, 1), back.box());
        assertEquals("given", back.name());
        assertEquals("stone", back.blockAt(0, 0, 0));
        assertEquals("air", back.blockAt(1, 0, 0));
        assertEquals("air", back.blockAt(0, 0, 1));
        assertEquals("stone", back.blockAt(1, 0, 1));
    }

    /** What nobody read is written as air, which is the nearest thing a schematic has. */
    @Test
    void unreadBlocksAreWrittenAsAir() throws IOException {
        Region box = new Region(0, 0, 0, 1, 0, 0);
        Snapshot kept = Snapshot.blank("r-0001", null, box, WHEN, "paper:25565")
                .with(new Region(0, 0, 0, 0, 0, 0), List.of("stone"), List.of(run(0, 1)));

        Snapshot back = Schematic.read(Schematic.write(kept, null), "r-0002", null, 1_000, WHEN, "file");

        assertEquals("stone", back.blockAt(0, 0, 0));
        assertEquals("air", back.blockAt(1, 0, 0));
        assertEquals(0, back.unread());
    }

    @Test
    void aFileOverTheLimitAndAFileThatIsNotASchematicAreRefusedWithTheReason() throws IOException {
        Region box = new Region(0, 0, 0, 9, 0, 9);
        Snapshot kept = Snapshot.blank("r-0001", null, box, WHEN, "paper:25565")
                .with(box, List.of("stone"), List.of(run(0, 100)));
        byte[] file = Schematic.write(kept, null);

        IOException tooBig = assertThrows(IOException.class, () -> Schematic.read(file, "r-0002", null, 99, WHEN, "file"));
        assertTrue(tooBig.getMessage().contains("holds 100 blocks"), tooBig.getMessage());

        IOException notOne = assertThrows(IOException.class, () -> Schematic.read(
                Nbt.write(new Nbt.Root("", Map.of("Level", Map.of()))), "r-0002", null, 99, WHEN, "file"));
        assertTrue(notOne.getMessage().contains("not a Sponge schematic"), notOne.getMessage());

        assertThrows(IOException.class, () -> Schematic.read(new byte[] {1, 2, 3}, "r-0002", null, 99, WHEN, "file"));
    }
}
