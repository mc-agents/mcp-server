package dev.mcagents.mcp.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * {@code [uint32 BE length][uint8 type][payload]}, where length counts the type byte.
 *
 * <p>Reading is blocking and one frame at a time. The caller owns the thread, which is what lets a
 * link be handled by a single reader and keeps frames applied in arrival order — {@code
 * run-command} depends on events landing in the ring buffer before the result that follows them.
 */
public final class FrameCodec {

    private FrameCodec() {}

    public static void write(OutputStream out, Frame frame) throws IOException {
        switch (frame) {
            case Frame.Json json -> {
                if (json.payload().length > Frame.MAX_JSON_BYTES) {
                    throw new ProtocolViolation(ProtocolViolation.Code.FRAME_TOO_LARGE,
                            "a JSON frame of %d bytes exceeds the %d byte limit"
                                    .formatted(json.payload().length, Frame.MAX_JSON_BYTES));
                }
                writeHeader(out, Frame.JSON, json.payload().length);
                out.write(json.payload());
            }
            case Frame.Blob blob -> {
                writeHeader(out, Frame.BLOB, 16 + blob.content().length);
                out.write(uuidBytes(blob.id()));
                out.write(blob.content());
            }
        }
        out.flush();
    }

    public static Frame read(InputStream in) throws IOException {
        long length = Integer.toUnsignedLong(readInt(in));

        if (length < 1) {
            throw new ProtocolViolation(ProtocolViolation.Code.MALFORMED_JSON,
                    "a frame must carry at least a type byte");
        }
        if (length > Frame.MAX_FRAME_BYTES) {
            throw new ProtocolViolation(ProtocolViolation.Code.FRAME_TOO_LARGE,
                    "a frame of %d bytes exceeds the %d byte limit".formatted(length, Frame.MAX_FRAME_BYTES));
        }

        int type = in.read();
        if (type < 0) {
            throw new EOFException("the link closed between a frame header and its type");
        }

        byte[] payload = readFully(in, (int) length - 1);

        return switch (type) {
            case Frame.JSON -> {
                if (payload.length > Frame.MAX_JSON_BYTES) {
                    throw new ProtocolViolation(ProtocolViolation.Code.FRAME_TOO_LARGE,
                            "a JSON frame of %d bytes exceeds the %d byte limit"
                                    .formatted(payload.length, Frame.MAX_JSON_BYTES));
                }
                yield new Frame.Json(payload);
            }
            case Frame.BLOB -> {
                if (payload.length < 16) {
                    throw new ProtocolViolation(ProtocolViolation.Code.MALFORMED_JSON,
                            "a blob frame must start with a 16 byte id");
                }
                ByteBuffer buffer = ByteBuffer.wrap(payload);
                UUID id = new UUID(buffer.getLong(), buffer.getLong());
                byte[] content = new byte[payload.length - 16];
                buffer.get(content);
                yield new Frame.Blob(id, content);
            }
            default -> throw new ProtocolViolation(ProtocolViolation.Code.BAD_FRAME_TYPE,
                    "frame type 0x%02x is not one this protocol defines".formatted(type));
        };
    }

    private static void writeHeader(OutputStream out, int type, int payloadLength) throws IOException {
        int length = payloadLength + 1;
        out.write(length >>> 24);
        out.write(length >>> 16);
        out.write(length >>> 8);
        out.write(length);
        out.write(type);
    }

    private static int readInt(InputStream in) throws IOException {
        byte[] header = readFully(in, 4);
        return ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
    }

    /*
    A frame arrives in as many reads as the network feels like giving, so a partial read is normal
    and only a closed link is not.
    */
    private static byte[] readFully(InputStream in, int count) throws IOException {
        byte[] buffer = new byte[count];
        int read = 0;
        while (read < count) {
            int step = in.read(buffer, read, count - read);
            if (step < 0) {
                throw new EOFException("the link closed %d bytes into a %d byte frame".formatted(read, count));
            }
            read += step;
        }
        return buffer;
    }

    private static byte[] uuidBytes(UUID id) {
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }
}
