package kr.junhyung.mcagents.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A shared bearer token on the MCP endpoint.
 *
 * <p>Configuring no token leaves it open, which is the right default for a laptop and the wrong one
 * anywhere else: the Helm chart sets one, and the README says what that means. Actuator is not
 * behind it, because a probe cannot carry a credential and there is nothing behind the probe.
 */
public class BearerTokenFilter extends OncePerRequestFilter {

    private static final Pattern BEARER = Pattern.compile("^Bearer[ \t]+(.+)$", Pattern.CASE_INSENSITIVE);

    private final byte[] expected;

    public BearerTokenFilter(String token) {
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    static String presented(String header) {
        if (header == null) {
            return null;
        }
        Matcher matcher = BEARER.matcher(header.trim());
        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * Compares bytes rather than decoded characters, and in constant time. Two tokens of different
     * lengths are refused before the comparison, which is what {@code MessageDigest.isEqual} does
     * anyway; the length is not the secret.
     */
    static boolean matches(byte[] expected, String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (matches(expected, presented(request.getHeader("Authorization")))) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer realm=\"mc-agents\"");
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":\"unauthorized\",\"message\":\"A valid Bearer token is required.\"}");
    }
}
