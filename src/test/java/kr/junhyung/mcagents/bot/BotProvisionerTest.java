package kr.junhyung.mcagents.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What join-server asks the operator for. The object is the contract with the operator, and the
 * link token half of it is what a bot started here needs to be admitted by a server that requires
 * one: a MinecraftBot declared without the ref dials in, is refused, and the join waits on nothing.
 */
class BotProvisionerTest {

    private static Map<?, ?> spec(BotProvisioner provisioner) {
        return (Map<?, ?>) provisioner.declare("alice", "alice", "azalea", "26.1.2", null)
                .getAdditionalProperties().get("spec");
    }

    @Test
    void aBotIsPointedAtTheLinkSecretThisServerWasToldAbout() {
        BotProvisioner provisioner = new BotProvisioner(null, "game", "mc-agents.game.svc", 8765, null,
                "mc-agents-mcp-server-link");

        assertEquals(Map.of("name", "mc-agents-mcp-server-link", "key", "token"),
                spec(provisioner).get("linkTokenSecretRef"));
    }

    /* Nothing in the object for a server that was told no secret, so an operator from before the ref exists takes it. */
    @Test
    void aServerWithoutALinkSecretDeclaresBotsWithoutTheRef() {
        BotProvisioner provisioner = new BotProvisioner(null, "game", "mc-agents.game.svc", 8765, null, null);

        assertFalse(spec(provisioner).containsKey("linkTokenSecretRef"));
    }
}
