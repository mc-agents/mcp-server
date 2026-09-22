package kr.junhyung.mcagents.schem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Minecraft's named binary tags, as far as a schematic needs them.
 *
 * <p>A compound is a {@code Map<String, Object>}, a list a {@code List<Object>}, and every other
 * tag the Java value it plainly is: a Byte, a Short, an Integer, a Long, a Float, a Double, a
 * String, a byte[], an int[] or a long[]. That is enough to read what WorldEdit writes and write
 * what it reads, and it keeps the schematic code about schematics.
 *
 * <p>A list carries its element type on the wire and an empty one carries {@code TAG_End}, which
 * is why writing one asks what it holds rather than what it was declared as.
 */
public final class Nbt {

    private static final int END = 0;
    private static final int BYTE = 1;
    private static final int SHORT = 2;
    private static final int INT = 3;
    private static final int LONG = 4;
    private static final int FLOAT = 5;
    private static final int DOUBLE = 6;
    private static final int BYTE_ARRAY = 7;
    private static final int STRING = 8;
    private static final int LIST = 9;
    private static final int COMPOUND = 10;
    private static final int INT_ARRAY = 11;
    private static final int LONG_ARRAY = 12;

    /** A whole file: the root compound and the name it was written under. */
    public record Root(String name, Map<String, Object> compound) {}

    private Nbt() {}

    /** Gzipped or not, since a hand-made file is sometimes neither compressed nor named. */
    public static Root read(byte[] bytes) throws IOException {
        boolean gzipped = bytes.length > 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b;

        try (DataInputStream in = new DataInputStream(gzipped
                ? new GZIPInputStream(new ByteArrayInputStream(bytes))
                : new ByteArrayInputStream(bytes))) {
            int type = in.readUnsignedByte();

            if (type != COMPOUND) {
                throw new IOException("the file does not start with a compound tag");
            }

            String name = in.readUTF();

            return new Root(name, compound(in));
        }
    }

    public static byte[] write(Root root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeByte(COMPOUND);
            out.writeUTF(root.name());
            compound(out, root.compound());
        }
        return bytes.toByteArray();
    }

    private static Map<String, Object> compound(DataInputStream in) throws IOException {
        Map<String, Object> tags = new LinkedHashMap<>();

        while (true) {
            int type = in.readUnsignedByte();

            if (type == END) {
                return tags;
            }
            tags.put(in.readUTF(), payload(in, type));
        }
    }

    private static Object payload(DataInputStream in, int type) throws IOException {
        return switch (type) {
            case BYTE -> in.readByte();
            case SHORT -> in.readShort();
            case INT -> in.readInt();
            case LONG -> in.readLong();
            case FLOAT -> in.readFloat();
            case DOUBLE -> in.readDouble();
            case BYTE_ARRAY -> {
                byte[] array = new byte[length(in)];
                in.readFully(array);
                yield array;
            }
            case STRING -> in.readUTF();
            case LIST -> {
                int element = in.readUnsignedByte();
                int length = length(in);
                List<Object> list = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    list.add(payload(in, element));
                }
                yield list;
            }
            case COMPOUND -> compound(in);
            case INT_ARRAY -> {
                int[] array = new int[length(in)];
                for (int i = 0; i < array.length; i++) {
                    array[i] = in.readInt();
                }
                yield array;
            }
            case LONG_ARRAY -> {
                long[] array = new long[length(in)];
                for (int i = 0; i < array.length; i++) {
                    array[i] = in.readLong();
                }
                yield array;
            }
            default -> throw new IOException("tag type %d is not one NBT has".formatted(type));
        };
    }

    private static int length(DataInputStream in) throws IOException {
        int length = in.readInt();

        if (length < 0) {
            throw new IOException("a length of %d".formatted(length));
        }
        return length;
    }

    private static void compound(DataOutputStream out, Map<String, Object> tags) throws IOException {
        for (Map.Entry<String, Object> tag : tags.entrySet()) {
            out.writeByte(typeOf(tag.getValue()));
            out.writeUTF(tag.getKey());
            payload(out, tag.getValue());
        }
        out.writeByte(END);
    }

    private static void payload(DataOutputStream out, Object value) throws IOException {
        switch (value) {
            case Byte b -> out.writeByte(b);
            case Short s -> out.writeShort(s);
            case Integer i -> out.writeInt(i);
            case Long l -> out.writeLong(l);
            case Float f -> out.writeFloat(f);
            case Double d -> out.writeDouble(d);
            case byte[] array -> {
                out.writeInt(array.length);
                out.write(array);
            }
            case String s -> out.writeUTF(s);
            case List<?> list -> {
                out.writeByte(list.isEmpty() ? END : typeOf(list.getFirst()));
                out.writeInt(list.size());
                for (Object element : list) {
                    payload(out, element);
                }
            }
            case Map<?, ?> map -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> tags = (Map<String, Object>) map;
                compound(out, tags);
            }
            case int[] array -> {
                out.writeInt(array.length);
                for (int i : array) {
                    out.writeInt(i);
                }
            }
            case long[] array -> {
                out.writeInt(array.length);
                for (long l : array) {
                    out.writeLong(l);
                }
            }
            default -> throw new IOException("%s is not a value NBT can carry".formatted(value.getClass().getSimpleName()));
        }
    }

    private static int typeOf(Object value) throws IOException {
        return switch (value) {
            case Byte b -> BYTE;
            case Short s -> SHORT;
            case Integer i -> INT;
            case Long l -> LONG;
            case Float f -> FLOAT;
            case Double d -> DOUBLE;
            case byte[] a -> BYTE_ARRAY;
            case String s -> STRING;
            case List<?> l -> LIST;
            case Map<?, ?> m -> COMPOUND;
            case int[] a -> INT_ARRAY;
            case long[] a -> LONG_ARRAY;
            default -> throw new IOException("%s is not a value NBT can carry".formatted(value.getClass().getSimpleName()));
        };
    }
}
