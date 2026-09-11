package kr.junhyung.mcagents.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BearerTokenFilterTest {

    private static byte[] token(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void theHeaderShapesClientsActuallySendAreAccepted() {
        assertEquals("abc", BearerTokenFilter.presented("Bearer abc"));
        assertEquals("abc", BearerTokenFilter.presented("bearer abc"));
        assertEquals("abc", BearerTokenFilter.presented("Bearer\tabc"));
        assertEquals("abc", BearerTokenFilter.presented("  Bearer   abc  "));
        assertNull(BearerTokenFilter.presented("Basic abc"));
        assertNull(BearerTokenFilter.presented("Bearer"));
        assertNull(BearerTokenFilter.presented(null));
    }

    @Test
    void aTokenOfAnotherLengthIsRefusedRatherThanThrowing() {
        assertTrue(BearerTokenFilter.matches(token("secret"), "secret"));
        assertFalse(BearerTokenFilter.matches(token("secret"), "secre"));
        assertFalse(BearerTokenFilter.matches(token("secret"), "secretlonger"));
        assertFalse(BearerTokenFilter.matches(token("secret"), ""));
        assertFalse(BearerTokenFilter.matches(token("secret"), null));
    }

    /* Two strings that differ only outside ASCII must not compare equal after any normalisation. */
    @Test
    void comparisonIsOverBytesAndNotDecodedCharacters() {
        assertTrue(BearerTokenFilter.matches(token("café"), "café"));
        assertFalse(BearerTokenFilter.matches(token("café"), "cafe"));
    }
}
