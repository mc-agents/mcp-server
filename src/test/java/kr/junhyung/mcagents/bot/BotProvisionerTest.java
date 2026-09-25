package kr.junhyung.mcagents.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What join-server asks the operator for. The object is the contract with the operator, and the
 * two refs in it are the halves that go wrong quietly: a MinecraftBot declared without the profile
 * ref is built from the operator's own fallback profile, so every bot runs images nobody here
 * chose, and one declared without the link token ref dials in, is refused by a server that
 * requires a token, and the join waits on nothing.
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

    /*
    The cluster-scoped kind is the one worth declaring here, because it is the only one a caller has
    to ask for: MinecraftBotProfile is what the CRD fills in by itself. A ref that wrote the kind the
    operator defaults to instead of the one given would send every bot looking for a namespaced
    profile that does not exist, and the operator answers that with Failed, not with the profile asked
    for.
    */
    @Test
    void aBotIsBuiltFromTheProfileThisServerWasToldAbout() {
        BotProvisioner provisioner = new BotProvisioner(null, "game", "mc-agents.game.svc", 8765,
                new BotProvisioner.Profile("ClusterMinecraftBotProfile", "qa"), null);

        assertEquals(Map.of("kind", "ClusterMinecraftBotProfile", "name", "qa"),
                spec(provisioner).get("profileRef"));
    }

    /*
    No ref at all, which is not the same as an empty one: the CRD requires a name, so a bot declared
    with a profileRef that has none is refused on create, while a bot declared with none is the way
    the operator is asked for its own defaults.
    */
    @Test
    void aServerWithoutAProfileDeclaresBotsWithoutTheRef() {
        BotProvisioner provisioner = new BotProvisioner(null, "game", "mc-agents.game.svc", 8765, null, null);

        assertFalse(spec(provisioner).containsKey("profileRef"));
    }

    /*
    A profile named without a kind is the ordinary way to write one, and the CRD reads it as the
    namespaced kind. Defaulting it anywhere but in the record leaves a Profile that can exist with
    no kind, which reaches the operator as a ref it cannot resolve.
    */
    @Test
    void aProfileNamedWithoutAKindIsTheNamespacedOne() {
        BotProvisioner provisioner = new BotProvisioner(null, "game", "mc-agents.game.svc", 8765,
                new BotProvisioner.Profile(null, "qa"), null);

        assertEquals(Map.of("kind", "MinecraftBotProfile", "name", "qa"), spec(provisioner).get("profileRef"));
    }

    /*
    And a profile with no name is refused where it is built. Left to Map.of it is a
    NullPointerException with nothing in it, thrown while a join is being answered.
    */
    @Test
    void aProfileWithoutANameIsRefusedWhereItIsBuilt() {
        assertThrows(IllegalArgumentException.class, () -> new BotProvisioner.Profile("MinecraftBotProfile", " "));
    }
}
