package kr.junhyung.mcagents.probe;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one piece of Minecraft the MCP server speaks itself.
 *
 * <p>Everywhere else the rule is that the server knows nothing about the game. This is the
 * exception, and the reason is the reason the tool exists: {@code ping-server} has to answer when
 * no bot is connected, which is exactly when you want to know whether the thing you just restarted
 * is back. Asking a bot would make the tool useless in the only case it is needed.
 *
 * <p>The handshake is version-independent. Protocol 0 in the handshake means "I am only asking",
 * and every server since 1.7 answers it the same way, so this does not join the list of things
 * that need attention when a Minecraft version lands.
 */
public final class ServerListPing {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** What a server said about itself. {@code motd} is the server's own text, so it is untrusted. */
    public record Pong(String version, int protocol, int online, int max, String motd, long latencyMs) {}

    private ServerListPing() {}

    public static Pong ping(String host, int port, int timeoutMs) throws IOException {
        long started = System.nanoTime();

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());

            writePacket(out, handshake(host, port));
            writePacket(out, new byte[] {0x00});

            JsonNode status = MAPPER.readTree(readStatus(in));
            long latency = (System.nanoTime() - started) / 1_000_000;

            return new Pong(
                    text(status.path("version").path("name"), "unknown"),
                    status.path("version").path("protocol").asInt(-1),
                    status.path("players").path("online").asInt(-1),
                    status.path("players").path("max").asInt(-1),
                    motdOf(status.path("description")),
                    latency);
        }
    }

    private static byte[] handshake(String host, int port) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();

        body.write(0x00);
        writeVarInt(body, 0); // "I am only asking", so no version has to be named.
        writeString(body, host);
        body.write(port >>> 8);
        body.write(port & 0xFF);
        writeVarInt(body, 1); // next state: status

        return body.toByteArray();
    }

    private static String readStatus(DataInputStream in) throws IOException {
        readVarInt(in);

        if (readVarInt(in) != 0x00) {
            throw new IOException("the server answered a status request with something else");
        }

        byte[] json = new byte[readVarInt(in)];
        in.readFully(json);

        return new String(json, StandardCharsets.UTF_8);
    }

    /**
     * A MOTD is a chat component, a plain string, or a tree of them. Flattening it loses the
     * colours, which is the right trade for a line an agent reads to recognise a server.
     */
    private static String motdOf(JsonNode description) {
        if (description.isMissingNode()) {
            return "";
        }
        if (description.isString()) {
            return description.asString();
        }

        StringBuilder text = new StringBuilder(text(description.path("text"), ""));

        for (JsonNode child : description.path("extra")) {
            text.append(motdOf(child));
        }
        return text.toString();
    }

    private static String text(JsonNode node, String fallback) {
        return node.isString() ? node.asString() : fallback;
    }

    private static void writePacket(OutputStream out, byte[] body) throws IOException {
        ByteArrayOutputStream framed = new ByteArrayOutputStream();

        writeVarInt(framed, body.length);
        framed.write(body);

        new DataOutputStream(out).write(framed.toByteArray());
        out.flush();
    }

    private static void writeString(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);

        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        int remaining = value;

        do {
            int part = remaining & 0x7F;
            remaining >>>= 7;
            out.write(remaining == 0 ? part : part | 0x80);
        } while (remaining != 0);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;

        for (int shift = 0; shift < 35; shift += 7) {
            int read = in.read();

            if (read < 0) {
                throw new EOFException("the link closed inside a length prefix");
            }
            value |= (read & 0x7F) << shift;

            if ((read & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("a length prefix ran past five bytes");
    }
}
