package dev.mcagents.mcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mcagents.mcp.protocol.Frame;
import dev.mcagents.mcp.protocol.FrameCodec;
import dev.mcagents.mcp.protocol.ProtocolViolation;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FrameCodecTest {

    private static byte[] encode(Frame frame) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.write(out, frame);
        return out.toByteArray();
    }

    @Test
    void aJsonFrameSurvivesTheRoundTrip() throws IOException {
        byte[] payload = "{\"t\":\"pong\"}".getBytes(StandardCharsets.UTF_8);

        Frame back = FrameCodec.read(new ByteArrayInputStream(encode(new Frame.Json(payload))));

        assertArrayEquals(payload, assertInstanceOf(Frame.Json.class, back).payload());
    }

    @Test
    void aBlobKeepsItsIdSeparateFromItsContent() throws IOException {
        UUID id = UUID.fromString("a3f0c1d2-0000-4000-8000-000000000001");
        byte[] content = {(byte) 0x89, 'P', 'N', 'G'};

        Frame.Blob back = assertInstanceOf(Frame.Blob.class,
                FrameCodec.read(new ByteArrayInputStream(encode(new Frame.Blob(id, content)))));

        assertEquals(id, back.id());
        assertArrayEquals(content, back.content());
    }

    @Test
    void twoFramesInOneBufferAreReadInOrder() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.write(out, new Frame.Json("{\"t\":\"a\"}".getBytes(StandardCharsets.UTF_8)));
        FrameCodec.write(out, new Frame.Json("{\"t\":\"b\"}".getBytes(StandardCharsets.UTF_8)));

        InputStream in = new ByteArrayInputStream(out.toByteArray());

        assertEquals("{\"t\":\"a\"}", new String(((Frame.Json) FrameCodec.read(in)).payload(), StandardCharsets.UTF_8));
        assertEquals("{\"t\":\"b\"}", new String(((Frame.Json) FrameCodec.read(in)).payload(), StandardCharsets.UTF_8));
    }

    /*
    The network hands over a frame in as many pieces as it likes, and a reader that assumes one
    read per frame works on a loopback socket and fails across a cluster.
    */
    @Test
    void aFrameSplitAcrossReadsIsStillOneFrame() throws IOException {
        byte[] encoded = encode(new Frame.Json("{\"t\":\"hello\"}".getBytes(StandardCharsets.UTF_8)));

        InputStream dribble = new InputStream() {
            private int at;

            @Override
            public int read() {
                return at < encoded.length ? encoded[at++] & 0xFF : -1;
            }

            @Override
            public int read(byte[] buffer, int off, int len) {
                if (at >= encoded.length) {
                    return -1;
                }
                buffer[off] = encoded[at++];
                return 1;
            }
        };

        assertEquals("{\"t\":\"hello\"}",
                new String(((Frame.Json) FrameCodec.read(dribble)).payload(), StandardCharsets.UTF_8));
    }

    @Test
    void anUnknownFrameTypeIsAViolationRatherThanSilentlySkipped() {
        byte[] frame = {0, 0, 0, 1, (byte) 0x7F};

        ProtocolViolation thrown = assertThrows(ProtocolViolation.class,
                () -> FrameCodec.read(new ByteArrayInputStream(frame)));

        assertEquals(ProtocolViolation.Code.BAD_FRAME_TYPE, thrown.code());
    }

    @Test
    void anOversizedLengthIsRefusedBeforeAnythingIsAllocated() {
        byte[] frame = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, Frame.JSON};

        ProtocolViolation thrown = assertThrows(ProtocolViolation.class,
                () -> FrameCodec.read(new ByteArrayInputStream(frame)));

        assertEquals(ProtocolViolation.Code.FRAME_TOO_LARGE, thrown.code());
    }

    @Test
    void aLinkThatClosesMidFrameIsAnEndOfFileRatherThanACorruptMessage() throws IOException {
        byte[] truncated = new byte[6];
        System.arraycopy(encode(new Frame.Json("{\"t\":\"hello\"}".getBytes(StandardCharsets.UTF_8))),
                0, truncated, 0, 6);

        assertThrows(EOFException.class, () -> FrameCodec.read(new ByteArrayInputStream(truncated)));
    }
}
