package kr.junhyung.mcagents.fixture;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Tab list entries for players who are not there, sent to one player the way a proxy's global tab
 * list or a plugin's NPC skin sends them.
 *
 * <p>Six listed names in an order a hash map does not keep, so a list the bot sorts and one it
 * does not read differently; and one entry the server sends unlisted, which is how hyperfarm
 * carries the skin of a cross-server ghost -- a real tab list does not show it, and a bot that
 * listed every profile it held showed that player twice. The packet is not in the API, and the
 * server's classes are on the plugin's class path.
 */
final class Crowd {

    private static final String PACKET = "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket";

    static final List<String> LISTED = List.of("zed", "amy", "mike", "bea", "yuki", "cal");
    static final String UNLISTED = "ghostskin";

    private Crowd() {
    }

    static void send(Player player) {
        try {
            Class<?> entry = Class.forName(PACKET + "$Entry");
            Class<?> profile = Class.forName("com.mojang.authlib.GameProfile");
            Class<?> gameType = Class.forName("net.minecraft.world.level.GameType");
            Constructor<?> newProfile = profile.getConstructor(UUID.class, String.class);
            Constructor<?> newEntry = entry.getConstructor(UUID.class, profile, boolean.class, int.class, gameType,
                Class.forName("net.minecraft.network.chat.Component"), boolean.class, int.class,
                Class.forName("net.minecraft.network.chat.RemoteChatSession$Data"));
            Object survival = constant(gameType, "SURVIVAL");

            List<Object> entries = new ArrayList<>();
            for (String name : LISTED) {
                entries.add(newEntry.newInstance(idOf(name), newProfile.newInstance(idOf(name), name), true, 0,
                    survival, null, true, 0, null));
            }
            entries.add(newEntry.newInstance(idOf(UNLISTED), newProfile.newInstance(idOf(UNLISTED), UNLISTED), false, 0,
                survival, null, true, 0, null));

            EnumSet<?> actions = actions(Class.forName(PACKET + "$Action"),
                "ADD_PLAYER", "UPDATE_LISTED", "UPDATE_GAME_MODE", "UPDATE_LATENCY");
            Object packet = Class.forName(PACKET).getConstructor(EnumSet.class, List.class).newInstance(actions, entries);
            send(player, packet);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not send the crowd", e);
        }
    }

    static void remove(Player player) {
        try {
            List<UUID> ids = new ArrayList<>();
            for (String name : LISTED) {
                ids.add(idOf(name));
            }
            ids.add(idOf(UNLISTED));
            Object packet = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket")
                .getConstructor(List.class).newInstance(ids);
            send(player, packet);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not remove the crowd", e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EnumSet<?> actions(Class<?> type, String... names) {
        EnumSet set = EnumSet.noneOf((Class<? extends Enum>) type);
        for (String name : names) {
            set.add(constant(type, name));
        }
        return set;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object constant(Class<?> type, String name) {
        return Enum.valueOf((Class<? extends Enum>) type, name);
    }

    /** Offline-mode ids, as the server itself derives them, so a name maps to one id every time. */
    private static UUID idOf(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void send(Player player, Object packet) throws ReflectiveOperationException {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object connection = handle.getClass().getField("connection").get(handle);
        connection.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"))
            .invoke(connection, packet);
    }
}
