package kr.junhyung.mcagents.tool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.junhyung.mcagents.schem.Nbt;

/**
 * A region as a Sponge schematic, which is the file WorldEdit, FAWE, Litematica's converters and
 * every schematic site read and write.
 *
 * <p>Written as version 3 and read as 2 or 3, since WorldEdit 7.2 still writes 2 by default and the
 * two differ only in where the block table sits. The block order in the data is x, then z, then y,
 * fastest first -- the same walk a snapshot keeps -- so neither direction reorders anything.
 *
 * <p>What does not survive the trip: block entities (a chest's contents, a sign's text), biomes and
 * entities, none of which a snapshot holds. A block nothing read is written as air, there being no
 * such thing as an unread block in a schematic, and the download says so.
 */
public final class Schematic {

    /** What the game calls its world format, by the version a bot reports; the newest is the fallback. */
    static final Map<String, Integer> DATA_VERSIONS = Map.of("26.1.2", 4790);

    static final int LATEST_DATA_VERSION = 4790;

    private static final String VANILLA = "minecraft:";

    private Schematic() {}

    public static byte[] write(Snapshot snapshot, String mcVersion) throws IOException {
        return write(snapshot, mcVersion, java.util.function.UnaryOperator.identity());
    }

    /**
     * @param filed what a palette entry is written as: a custom block by the id WorldEdit files it
     *              under, so the plugin's own schematic reader knows it, and anything else as itself
     */
    public static byte[] write(Snapshot snapshot, String mcVersion, java.util.function.UnaryOperator<String> filed) throws IOException {
        Region box = snapshot.box();
        Map<String, Object> palette = new LinkedHashMap<>();
        List<String> names = snapshot.palette();

        for (int entry = 0; entry < names.size(); entry++) {
            palette.putIfAbsent(qualified(filed.apply(names.get(entry))), entry);
        }

        int air = names.indexOf("air");

        if (air < 0 && snapshot.unread() > 0) {
            air = names.size();
            palette.put(VANILLA + "air", air);
        }

        ByteArrayOutputStream data = new ByteArrayOutputStream((int) box.blocks());

        for (short block : snapshot.raw()) {
            varint(data, block == Snapshot.UNREAD ? air : block);
        }

        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("Palette", palette);
        blocks.put("Data", data.toByteArray());
        blocks.put("BlockEntities", List.of());

        Map<String, Object> worldEdit = new LinkedHashMap<>();
        worldEdit.put("Version", "mc-agents");
        worldEdit.put("Origin", new int[] {box.minX(), box.minY(), box.minZ()});

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("Date", snapshot.created().toEpochMilli());
        metadata.put("WorldEdit", worldEdit);
        if (snapshot.name() != null) {
            metadata.put("Name", snapshot.name());
        }

        Map<String, Object> schematic = new LinkedHashMap<>();
        schematic.put("Version", 3);
        schematic.put("DataVersion", mcVersion == null ? LATEST_DATA_VERSION : DATA_VERSIONS.getOrDefault(mcVersion, LATEST_DATA_VERSION));
        schematic.put("Width", (short) box.sizeX());
        schematic.put("Height", (short) box.sizeY());
        schematic.put("Length", (short) box.sizeZ());
        schematic.put("Offset", new int[] {0, 0, 0});
        schematic.put("Metadata", metadata);
        schematic.put("Blocks", blocks);

        return Nbt.write(new Nbt.Root("", Map.of("Schematic", schematic)));
    }

    /**
     * @param limit the most blocks the file may describe, which is the caller's store to size
     */
    public static Snapshot read(byte[] bytes, String id, String name, long limit, Instant now, String origin) throws IOException {
        Map<String, Object> schematic = schematicOf(Nbt.read(bytes));
        int version = intOf(schematic.get("Version"), "Version");
        Map<String, Object> table;
        byte[] data;

        if (version >= 3) {
            Map<String, Object> blocks = compoundOf(schematic.get("Blocks"), "Blocks");
            table = compoundOf(blocks.get("Palette"), "Blocks.Palette");
            data = bytesOf(blocks.get("Data"), "Blocks.Data");
        } else {
            table = compoundOf(schematic.get("Palette"), "Palette");
            data = bytesOf(schematic.get("BlockData"), "BlockData");
        }

        int width = shortOf(schematic.get("Width"), "Width");
        int height = shortOf(schematic.get("Height"), "Height");
        int length = shortOf(schematic.get("Length"), "Length");
        long total = (long) width * height * length;

        if (total == 0) {
            throw new IOException("the schematic is %d x %d x %d, which holds nothing".formatted(width, height, length));
        }
        if (total > limit) {
            throw new IOException("the schematic holds %d blocks, and the most this server keeps as one region is %d"
                    .formatted(total, limit));
        }

        String[] byIndex = new String[table.size()];
        List<String> palette = new ArrayList<>();

        for (Map.Entry<String, Object> entry : table.entrySet()) {
            int index = intOf(entry.getValue(), "Palette." + entry.getKey());

            if (index < 0 || index >= byIndex.length || byIndex[index] != null) {
                throw new IOException("the palette gives %s the index %d, which is out of order".formatted(entry.getKey(), index));
            }
            byIndex[index] = plain(entry.getKey());
        }
        for (String block : byIndex) {
            if (block == null) {
                throw new IOException("the palette skips an index");
            }
            palette.add(block);
        }
        if (palette.size() > Snapshot.MAX_PALETTE) {
            throw new IOException("the palette has %d kinds of block, and %d is the most a region holds"
                    .formatted(palette.size(), Snapshot.MAX_PALETTE));
        }

        short[] blocks = new short[(int) total];
        int at = 0;

        for (int i = 0; i < blocks.length; i++) {
            int value = 0;
            int shift = 0;

            while (true) {
                if (at >= data.length) {
                    throw new IOException("the block data ends after %d of %d blocks".formatted(i, blocks.length));
                }
                int b = data[at++] & 0xff;
                value |= (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
                if (shift > 28) {
                    throw new IOException("a block index runs past what an int holds");
                }
            }
            if (value >= palette.size()) {
                throw new IOException("block %d names palette entry %d, and the palette has %d".formatted(i, value, palette.size()));
            }
            blocks[i] = (short) value;
        }

        int[] where = originOf(schematic);
        Region box = new Region(where[0], where[1], where[2],
                where[0] + width - 1, where[1] + height - 1, where[2] + length - 1);
        String given = name != null ? name : nameOf(schematic);

        return new Snapshot(id, given, box, palette, blocks, now, origin);
    }

    /** Version 3 wraps the schematic in a root compound; version 2 is the root compound, named "Schematic". */
    private static Map<String, Object> schematicOf(Nbt.Root root) throws IOException {
        Object wrapped = root.compound().get("Schematic");

        if (wrapped instanceof Map<?, ?>) {
            return compoundOf(wrapped, "Schematic");
        }
        if (root.compound().containsKey("Palette") || root.compound().containsKey("BlockData")) {
            return root.compound();
        }
        throw new IOException("the file is not a Sponge schematic: it has no Schematic compound and no Palette");
    }

    /**
     * Where the box's lower corner was in the world it came from: WorldEdit's origin -- where the
     * player stood at //copy -- plus the offset from there to the region's minimum point, which is
     * what the format's Offset holds. A file with neither starts at zero.
     */
    private static int[] originOf(Map<String, Object> schematic) {
        int[] origin = schematic.get("Metadata") instanceof Map<?, ?> metadata
                && metadata.get("WorldEdit") instanceof Map<?, ?> worldEdit
                && worldEdit.get("Origin") instanceof int[] at && at.length == 3
                ? at : new int[] {0, 0, 0};
        int[] offset = schematic.get("Offset") instanceof int[] by && by.length == 3 ? by : new int[] {0, 0, 0};

        return new int[] {origin[0] + offset[0], origin[1] + offset[1], origin[2] + offset[2]};
    }

    private static String nameOf(Map<String, Object> schematic) {
        return schematic.get("Metadata") instanceof Map<?, ?> metadata && metadata.get("Name") instanceof String name
                ? name
                : null;
    }

    private static String qualified(String block) {
        return block.indexOf(':') < 0 ? VANILLA + block : block;
    }

    private static String plain(String block) {
        return block.startsWith(VANILLA) ? block.substring(VANILLA.length()) : block;
    }

    private static void varint(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7f) != 0) {
            out.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static Map<String, Object> compoundOf(Object value, String what) throws IOException {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> compound = (Map<String, Object>) map;
            return compound;
        }
        throw new IOException("%s is missing or is not a compound".formatted(what));
    }

    private static byte[] bytesOf(Object value, String what) throws IOException {
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        throw new IOException("%s is missing or is not a byte array".formatted(what));
    }

    private static int intOf(Object value, String what) throws IOException {
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IOException("%s is missing or is not a number".formatted(what));
    }

    /** A size is a short in the format, and a negative one is what a file over 32767 wide reads as. */
    private static int shortOf(Object value, String what) throws IOException {
        int size = intOf(value, what);

        if (size <= 0) {
            throw new IOException("%s is %d".formatted(what, size));
        }
        return size;
    }
}
