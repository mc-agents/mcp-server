package kr.junhyung.mcagents.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Against a loopback server that answers whatever the test tells it to. ping-server runs with no
 * bot, so what it allocates is decided by a Minecraft server it has never met.
 */
class ServerListPingTest {

    /** A server that reads the handshake and the request, then writes one status packet. */
    private static int serve(byte[] statusPacket) throws IOException {
        ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());

        Thread.ofVirtual().start(() -> {
            try (listener; Socket client = listener.accept()) {
                InputStream in = client.getInputStream();
                skipPacket(in);
                skipPacket(in);
                client.getOutputStream().write(statusPacket);
                client.getOutputStream().flush();
            } catch (IOException e) {
                // The client has what it needs, or has given up; either way the test decides.
            }
        });
        return listener.getLocalPort();
    }

    private static void skipPacket(InputStream in) throws IOException {
        int length = readVarInt(in);
        in.readNBytes(length);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; ; shift += 7) {
            int read = in.read();
            value |= (read & 0x7F) << shift;
            if ((read & 0x80) == 0) {
                return value;
            }
        }
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        int remaining = value;
        do {
            int part = remaining & 0x7F;
            remaining >>>= 7;
            out.write(remaining == 0 ? part : part | 0x80);
        } while (remaining != 0);
    }

    /** A status packet whose string prefix claims {@code claimed} bytes, whatever follows. */
    private static byte[] statusClaiming(int claimed, byte[] body) throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        packet.write(0x00);
        writeVarInt(packet, claimed);
        packet.write(body);

        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        writeVarInt(framed, packet.size());
        framed.write(packet.toByteArray());
        return framed.toByteArray();
    }

    @Test
    void aStatusIsReadBackAsThePongItDescribes() throws IOException {
        byte[] json = """
                {"version":{"name":"Paper 26.1.2","protocol":774},"players":{"online":2,"max":20},
                 "description":{"text":"Hello ","extra":[{"text":"world","color":"gold"}]}}"""
                .getBytes(StandardCharsets.UTF_8);

        ServerListPing.Pong pong = ServerListPing.ping("127.0.0.1", serve(statusClaiming(json.length, json)), 2_000);

        assertEquals("Paper 26.1.2", pong.version());
        assertEquals(774, pong.protocol());
        assertEquals(2, pong.online());
        assertEquals(20, pong.max());
        assertEquals("Hello world", pong.motd());
    }

    /**
     * The length is the server's claim, and {@code new byte[claim]} used to be the next line. A
     * server naming two gigabytes takes a pod with a one gigabyte limit down, from a tool that
     * runs without a bot; the claim is refused before anything is allocated.
     */
    @Test
    void aStatusClaimingMoreThanAProtocolStringCanHoldIsRefusedBeforeItIsAllocated() throws IOException {
        int port = serve(statusClaiming(Integer.MAX_VALUE - 8, "{}".getBytes(StandardCharsets.UTF_8)));

        IOException refused = assertThrows(IOException.class, () -> ServerListPing.ping("127.0.0.1", port, 2_000));

        assertTrue(refused.getMessage().contains(String.valueOf(Integer.MAX_VALUE - 8)), refused.getMessage());
        assertTrue(refused.getMessage().contains("the most it can be is"), refused.getMessage());
    }
}
