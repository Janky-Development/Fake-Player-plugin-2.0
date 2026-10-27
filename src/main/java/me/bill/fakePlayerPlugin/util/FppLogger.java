package me.bill.fakePlayerPlugin.util;

import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.permission.Perm;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class FppLogger {

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";

    // FPP 2.0 "Bot Console" palette - violet / lime, see language/en.yml header for the full reference.
    private static final String VIOLET = "\u001B[38;2;167;139;250m"; // violet #A78BFA

    private static final String WHITE = "\u001B[38;2;238;236;247m"; // ink #EEECF7

    private static final String YELLOW = "\u001B[38;2;186;255;79m"; // lime #BAFF4F

    private static final String GREEN = "\u001B[38;2;186;255;79m"; // lime #BAFF4F


    private static final String RED = "\u001B[38;2;255;106;92m"; // red #FF6A5C

    private static final String GRAY = "\u001B[38;2;150;145;171m"; // mute #9691AB

    private static final String CYAN = "\u001B[38;2;202;186;255m"; // violet-soft #CABAFF

    private static final String DARK = "\u001B[38;2;95;91;115m"; // mute-2 #5F5B73

    private static final String TAG = BOLD + VIOLET + "[ꜰᴘᴘ]" + RESET;

    private static final int RULE_WIDTH = 50;

    private static final int KEY_WIDTH = 18;

    /** Creator credit shown in the startup/shutdown banners. */
    private static final String CREATOR = "F_PP";

    private static Logger logger;

    private FppLogger() {}

    public static void init(Logger javaLogger) {
        logger = javaLogger;
    }

    public static void info(String message) {
        logger.info(TAG + " " + WHITE + message + RESET);
    }

    public static void success(String message) {
        logger.info(TAG + " " + GREEN + message + RESET);
    }

    public static void warn(String message) {
        logger.warning(TAG + " " + YELLOW + message + RESET);
    }

    public static void error(String message) {
        logger.severe(TAG + " " + RED + message + RESET);
    }

    public static void debug(String message) {
        debug("GENERAL", Config.isDebug(), message);
    }

    public static void debug(String topic, boolean enabled, String message) {
        if (!enabled) return;
        String label =
                (topic == null || topic.isBlank()) ? "DEBUG" : topic.trim().toUpperCase();
        logger.info(TAG + " " + GRAY + "[" + YELLOW + "DEBUG" + GRAY + "/" + CYAN + label + GRAY + "] " + YELLOW
                + message + RESET);

        if (Config.debugChatBroadcast()) {
            Component chatMsg = Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("[ꜰᴘᴘ DEBUG/").color(TextColor.fromHexString("#9691AB")))
                    .append(Component.text(label).color(TextColor.fromHexString("#CABAFF")))
                    .append(Component.text("] ").color(TextColor.fromHexString("#9691AB")))
                    .append(Component.text(message).color(TextColor.fromHexString("#BAFF4F")));
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (Perm.has(p, Perm.OP) || Perm.has(p, Perm.NOTIFY)) {
                    p.sendMessage(chatMsg);
                }
            }
        }
    }

    public static void highlight(String message) {
        logger.info(TAG + " " + BOLD + CYAN + message + RESET);
    }

    public static void rule() {
        logger.info(TAG + " " + DARK + "─".repeat(RULE_WIDTH) + RESET);
    }

    public static void boldRule() {
        logger.info(TAG + " " + GRAY + BOLD + "═".repeat(RULE_WIDTH) + RESET);
    }

    public static void section(String label) {
        String dashes = "─".repeat(Math.max(0, RULE_WIDTH - label.length() - 4));
        logger.info(TAG + " " + DARK + "── " + RESET + BOLD + WHITE + label + " " + DARK + dashes + RESET);
    }

    public static void kv(String key, Object value) {
        int dots = Math.max(1, KEY_WIDTH - key.length());
        String dotStr = DARK + ".".repeat(dots) + RESET;
        logger.info(TAG + " " + GRAY + "  " + WHITE + key + " " + dotStr + " " + VIOLET + value + RESET);
    }



    private static void stateRow(RowState state, String label, String detail) {
        String badge;
        String valueColor;
        switch (state) {
            case OK -> {
                badge = GREEN + "[+]" + RESET;
                valueColor = GREEN;
            }
            case WARN -> {
                badge = YELLOW + "[!]" + RESET;
                valueColor = YELLOW;
            }
            default -> {
                badge = GRAY + "[-]" + RESET;
                valueColor = GRAY;
            }
        }

        int dots = Math.max(1, KEY_WIDTH - label.length());
        String dotStr = DARK + ".".repeat(dots) + RESET;
        logger.info(TAG
                + " "
                + GRAY
                + "  "
                + badge
                + " "
                + WHITE
                + label
                + " "
                + dotStr
                + " "
                + valueColor
                + detail
                + RESET);
    }

    @SuppressWarnings("unused")
    public static void blank() {
        logger.info("");
    }

    public static void printStartupBanner(
            String version,
            String authors,
            int namePoolSize,
            String dbState,
            int dbSchemaVersion,
            boolean persistEnabled,
            boolean taskPersistEnabled,
            boolean luckPermsFound,
            boolean chunkLoading,
            int maxBots,
            boolean metricsActive,
            String configVersion,
            int backupCount,
            long startupMs) {
        boldRule();
        info("  " + BOLD + VIOLET + "FakePlayerPlugin" + RESET + WHITE + " v" + version + RESET);
        info("  " + GRAY + "real fake players for Paper & Folia 1.21+" + RESET);
        info("  " + GRAY + "created by " + WHITE + CREATOR + RESET);
        rule();

        section("Runtime");
        kv("Build", BuildInfo.summary());
        String dbDisplay = dbSchemaVersion > 0 ? dbState + "  (schema v" + dbSchemaVersion + ")" : dbState;
        stateRow(resolveDbState(dbState), "Database", dbDisplay);
        kv("Config version", configVersion);

        section("Features");
        stateRow(persistEnabled ? RowState.OK : RowState.OFF, "Persistence", onOff(persistEnabled));
        stateRow(
                taskPersistEnabled ? RowState.OK : RowState.OFF,
                "Task persistence",
                taskPersistEnabled ? "db + yaml" : onOff(false));
        stateRow(chunkLoading ? RowState.OK : RowState.OFF, "Chunk loading", onOff(chunkLoading));

        section("Integrations");
        stateRow(luckPermsFound ? RowState.OK : RowState.OFF, "LuckPerms", onOff(luckPermsFound));
        stateRow(metricsActive ? RowState.OK : RowState.OFF, "Metrics", onOff(metricsActive));

        section("Limits");
        kv("Max bots", maxBots == 0 ? "unlimited" : maxBots);

        rule();
        success("  Ready in " + startupMs + "ms - type /fpp help to begin");
        boldRule();
    }

    public static void printShutdownBanner(int botsRemoved, long uptimeMs) {
        boldRule();
        highlight("  FakePlayerPlugin  -  shutting down");
        info("  " + GRAY + "created by " + WHITE + CREATOR + RESET);
        rule();
        section("Session");
        kv("Build", BuildInfo.summary());
        kv("Uptime", formatUptime(uptimeMs));
        kv("Bots removed", botsRemoved);
        rule();
        info("  " + (botsRemoved > 0 ? "All bots despawned" : "No bots were active") + " - see you next time!");
        boldRule();
    }

    private static String formatUptime(long ms) {
        long totalSec = ms / 1_000;
        long hours = totalSec / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;
        if (hours > 0) return hours + "h " + minutes + "m " + seconds + "s";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    private static String onOff(boolean enabled) {
        return enabled ? "enabled" : "disabled";
    }

    private static RowState resolveDbState(String dbState) {
        if (dbState == null) return RowState.WARN;
        String s = dbState.toLowerCase();
        if (s.contains("failed")) return RowState.WARN;
        if (s.contains("disabled") || s.contains("none")) return RowState.OFF;
        return RowState.OK;
    }

    private enum RowState {
        OK,
        WARN,
        OFF
    }
}
