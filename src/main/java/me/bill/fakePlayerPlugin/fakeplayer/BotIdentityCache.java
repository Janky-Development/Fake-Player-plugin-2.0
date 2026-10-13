package me.bill.fakePlayerPlugin.fakeplayer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;

/**
 * Deterministic bot UUIDs, derived from the bot name alone - no storage, no Mojang contact.
 *
 * <p>This used to be a stateful name→UUID identity cache backed by YAML/DB with Mojang premium-UUID
 * resolution; that whole system was removed. What remains is the pure derivation, so the class is
 * now static-only. Old persisted identities (the {@code identities.by-name} YAML section /
 * {@code bot_identities} DB rows) are left on disk untouched but are no longer read or written.
 */
public final class BotIdentityCache {

    private static final String OFFLINE_UUID_NAMESPACE = "OfflinePlayer:";
    private static final ThreadLocal<MessageDigest> MD5 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    });

    private BotIdentityCache() {}

    /**
     * High 64 bits shared by every bot UUID - makes bots instantly recognizable ("FB07" ≈ F-BOT)
     * and guarantees zero collision with real accounts (premium UUIDs are random v4, offline-mode
     * ones are MD5 v3; neither can land in a fixed-prefix range). Deliberately non-zero: Floodgate
     * gives Bedrock players UUIDs with {@code getMostSignificantBits() == 0}, and plugins detect
     * them by exactly that check, so an all-zero prefix would misclassify bots as Bedrock players.
     */
    private static final long UUID_MSB_SEQUENTIAL = 0xFB07_0000_0000_0000L;

    /** Same prefix, low bit set: marks a custom-named bot whose low 64 bits are a name hash. */
    private static final long UUID_MSB_NAMED = 0xFB07_0000_0000_0001L;


    /**
     * Deterministic bot UUID, derived from the name alone - no storage, no Mojang contact.
     *
     * <p>Default sequential names embed their number directly: {@code bot} →
     * {@code fb070000-0000-0000-0000-000000000001}, {@code bot2} → {@code …-000000000002}, and so
     * on. Custom names get the same recognizable prefix with a 64-bit hash of the lowercase name in
     * the low bits (UUIDs are hex-only, so the name itself can't be embedded literally); the marker
     * bit in the high half keeps the two forms from ever colliding - a bot custom-named
     * {@code bot1} hashes instead of claiming {@code bot}'s number 1.
     */
    public static UUID deterministicBotUuid(String botName) {
        long seq = canonicalSequentialNumber(botName);
        if (seq > 0) return new UUID(UUID_MSB_SEQUENTIAL, seq);
        return new UUID(UUID_MSB_NAMED, nameHash64(botName));
    }



    /**
     * The bot number when (and only when) the name is exactly what {@code nextSequentialName}
     * generates for that number - {@code bot} → 1, {@code bot2} → 2 … Non-canonical spellings
     * ({@code bot1}, {@code bot02}) return -1 so they fall through to the named-hash form instead
     * of colliding with the canonical name's UUID.
     */
    private static long canonicalSequentialNumber(String botName) {
        if (botName == null) return -1;
        String lower = botName.toLowerCase(Locale.ROOT);
        if (lower.equals("bot")) return 1;
        if (!lower.startsWith("bot")) return -1;
        String digits = lower.substring(3);
        if (digits.isEmpty() || digits.length() > 18) return -1;
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) return -1;
        }
        long n;
        try {
            n = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
        return n >= 2 && lower.equals("bot" + n) ? n : -1;
    }

    private static long nameHash64(String botName) {
        MessageDigest md5 = MD5.get();
        md5.reset();
        byte[] bytes = md5.digest(("fpp-bot:" + normalizeKey(botName)).getBytes(StandardCharsets.UTF_8));
        long h = 0;
        for (int i = 0; i < 8; i++) h = (h << 8) | (bytes[i] & 0xffL);
        return h;
    }

    /**
     * Legacy pre-2.0 bot UUID (vanilla offline-mode formula). Only used to recognize and migrate
     * old persisted identities - new UUIDs come from {@link #deterministicBotUuid}.
     */
    public static UUID offlineModeUuid(String botName) {
        MessageDigest md5 = MD5.get();
        md5.reset();
        byte[] bytes = md5.digest((OFFLINE_UUID_NAMESPACE + String.valueOf(botName)).getBytes(StandardCharsets.UTF_8));
        bytes[6] &= 0x0f;
        bytes[6] |= 0x30;
        bytes[8] &= 0x3f;
        bytes[8] |= 0x80;
        long most = 0;
        long least = 0;
        for (int i = 0; i < 8; i++) most = (most << 8) | (bytes[i] & 0xffL);
        for (int i = 8; i < 16; i++) least = (least << 8) | (bytes[i] & 0xffL);
        return new UUID(most, least);
    }

    private static String normalizeKey(String botName) {
        return botName == null ? "" : botName.toLowerCase(Locale.ROOT);
    }
}
