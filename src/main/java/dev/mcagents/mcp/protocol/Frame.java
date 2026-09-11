package dev.mcagents.mcp.protocol;

import java.util.UUID;

/**
 * One message on the wire. A frame is a message; there is no other boundary.
 */
public sealed interface Frame {

    int JSON = 0x00;
    int BLOB = 0x01;

    int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    int MAX_JSON_BYTES = 1024 * 1024;

    record Json(byte[] payload) implements Frame {}

    record Blob(UUID id, byte[] content) implements Frame {}
}
