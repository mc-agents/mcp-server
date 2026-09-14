package kr.junhyung.mcagents.bot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The name a bot's {@code MinecraftBot} is created under, and the bot name one stands for.
 *
 * <p>A bot is named the way a player is -- {@code Qa_Bot1} -- and a Kubernetes object is not: its
 * name is lowercase letters, digits and hyphens. Creating the object under the bot's own name refused
 * every name with an underscore or a capital. So the object gets a name of its own and carries the
 * bot's in {@code spec.botName}, which the operator hands the pod as the name it dials in with.
 *
 * <p>The object's name is derived rather than stored anywhere, but it is never how a bot is found:
 * lookups go by the bot name the object declares, so two names that derive alike cannot be mistaken
 * for each other.
 */
public final class BotResourceName {

    /** A name Kubernetes takes as it is. Sixteen, because the operator dials in with at most that. */
    private static final Pattern AS_IS = Pattern.compile("^[a-z0-9]([-a-z0-9]{0,14}[a-z0-9])?$");

    /** What the operator accepts in {@code spec.botName}: a Minecraft username. */
    private static final Pattern BOT_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    /** The operator's limit on the name a pod dials in with. */
    private static final int DIALLED_LENGTH = 16;

    private static final int SUFFIX_HEX = 6;

    private BotResourceName() {}

    /**
     * The object name for a bot, or a refusal saying which names a cluster can start.
     *
     * <p>A name Kubernetes takes is used as it is, so a bot declared by hand under its own name and
     * one this server declares look the same in {@code kubectl}. Any other name is lowercased with
     * its underscores turned into hyphens, and a hash of the original goes on the end: without it
     * {@code Qa_Bot1} and {@code qa_bot1} would ask for the same object.
     */
    public static String of(String botName) {
        if (AS_IS.matcher(botName).matches()) {
            return botName;
        }
        if (!BOT_NAME.matcher(botName).matches()) {
            throw new IllegalArgumentException(
                    "a bot the cluster starts dials in with at most 16 characters, either letters, digits and underscores or lowercase letters, digits and hyphens, and \"%s\" is neither"
                            .formatted(botName));
        }

        String slug = botName.toLowerCase(Locale.ROOT).replace('_', '-').replaceAll("^-+|-+$", "");
        return (slug.isEmpty() ? "bot" : slug) + "-" + hash(botName);
    }

    /**
     * The value for {@code spec.botName}, or null when the object's own name already is the bot's.
     * The operator refuses a hyphen there, and a name that needs none has nothing to add.
     */
    public static String specBotName(String botName) {
        return AS_IS.matcher(botName).matches() ? null : botName;
    }

    /** The bot an existing object stands for, by the operator's own rule: the spec's name, else its own. */
    public static String declared(String objectName, String specBotName) {
        if (specBotName != null && !specBotName.isEmpty()) {
            return specBotName;
        }
        return objectName.length() > DIALLED_LENGTH ? objectName.substring(0, DIALLED_LENGTH) : objectName;
    }

    private static String hash(String botName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(botName.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, SUFFIX_HEX);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM has SHA-256", e);
        }
    }
}
