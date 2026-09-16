package kr.junhyung.mcagents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;

/** The two settings whose wrong value used to be accepted quietly. */
class WiringTest {

    private final Wiring wiring = new Wiring();

    private String bound(String bindHost, String token) {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        wiring.mcpBindAddress(bindHost, token).customize(factory);
        return factory.getAddress().getHostAddress();
    }

    /**
     * A server with no token is a laptop's and must not answer the coffee shop; one with a token
     * is a pod's and is reached through its Service. The override wins over both.
     */
    @Test
    void whereTheMcpPortListensFollowsFromTheToken() {
        assertEquals("127.0.0.1", bound("", ""));
        assertEquals("0.0.0.0", bound("", "letmein"));
        assertEquals("0.0.0.0", bound("0.0.0.0", ""));
        assertEquals("127.0.0.1", bound("127.0.0.1", "letmein"));
    }

    /* "alwyas" used to mean auto, and a bot pod turned up in whatever cluster the kubeconfig named. */
    @Test
    void aProvisionSettingThatIsNoneOfTheThreeIsRefusedAtStartup() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> wiring.botProvisioner("alwyas", "", "", "", "", 8765));

        assertEquals("MCP_BOTS_PROVISION is \"alwyas\"; it has to be auto, always or never", refused.getMessage());
    }

    @Test
    void aBotLimitBelowOneIsRefusedAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> wiring.botRegistry(0, new SimpleMeterRegistry()));
    }
}
