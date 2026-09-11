package kr.junhyung.mcagents.protocol;

import java.io.IOException;

/**
 * A breach of the wire contract, as opposed to a tool that failed. The link closes; no {@code
 * result} is produced. Keeping these apart is what lets a broken tool leave the session healthy.
 */
public final class ProtocolViolation extends IOException {

    public enum Code {
        FRAME_TOO_LARGE,
        BAD_FRAME_TYPE,
        MALFORMED_JSON,
        UNKNOWN_MESSAGE,
        MISSING_FIELD,
        HELLO_EXPECTED,
        HELLO_TWICE,
        DUPLICATE_CALL_ID,
        EVENT_SEQ_REGRESSION,
        BLOB_BEFORE_HELLO,
    }

    private static final long serialVersionUID = 1L;

    private final Code code;

    public ProtocolViolation(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
