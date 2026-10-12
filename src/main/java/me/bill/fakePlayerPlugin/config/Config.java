package me.bill.fakePlayerPlugin.config;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.util.FppLogger;

public final class Config {

    private static FakePlayerPlugin plugin;
    private static FileConfiguration cfg;
    private static FileConfiguration debugCfg;
    private static final Map<String, FileConfiguration> externalConfigs = new ConcurrentHashMap<>();

    private Config() {}

    public static void registerExternalConfig(String rootKey, FileConfiguration config) {
        if (rootKey == null || rootKey.isBlank() || config == null) return;
        externalConfigs.put(rootKey, config);
    }

    public static void unregisterExternalConfig(String rootKey, FileConfiguration config) {
        if (rootKey == null || config == null) return;
        externalConfigs.remove(rootKey, config);
    }

    private static FileConfiguration configFor(String path) {
        int dot = path.indexOf('.');
        String root = dot >= 0 ? path.substring(0, dot) : path;
        FileConfiguration external = externalConfigs.get(root);
        return external != null ? external : cfg;
    }

    private static boolean debugBool(String path, boolean def) {
        if (debugCfg == null) return def;
        return debugCfg.getBoolean(path, def);
    }

    public static boolean debugBoolValue(String path, boolean def) {
        return debugBool(path, def);
    }

    private static boolean bool(String path, boolean def) {
        return configFor(path).getBoolean(path, def);
    }

    private static int integer(String path, int def) {
        return configFor(path).getInt(path, def);
    }

    private static double decimal(String path, double def) {
        return configFor(path).getDouble(path, def);
    }

    private static String string(String path, String def) {
        return configFor(path).getString(path, def);
    }

    private static Object value(String path) {
        return configFor(path).get(path);
    }

    private static List<Map<?, ?>> mapList(String path) {
        return configFor(path).getMapList(path);
    }

    private static ConfigurationSection section(String path) {
        return configFor(path).getConfigurationSection(path);
    }

    public static void init(FakePlayerPlugin instance) {
        plugin = instance;
        reload();
    }

    public static void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        cfg = plugin.getConfig();
        cfg.options().copyDefaults(true);

        plugin.saveConfig();

        loadDebugConfig();
    }

    private static void loadDebugConfig() {
        try {
            java.io.File debugFile = new java.io.File(plugin.getDataFolder(), "debug.yml");
            if (!debugFile.exists()) {
                plugin.saveResource("debug.yml", false);
                Config.debugStartup("debug.yml created from template.");
            }

            debugCfg = YamlConfiguration.loadConfiguration(debugFile);
            var defaultsStream = plugin.getResource("debug.yml");
            if (defaultsStream != null) {
                try (InputStreamReader reader = new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)) {
                    debugCfg.setDefaults(YamlConfiguration.loadConfiguration(reader));
                }
            }
            debugCfg.options().copyDefaults(true);
            Config.debugStartup("debug.yml loaded.");
        } catch (Exception e) {
            FppLogger.warn("Failed to load debug.yml: " + e.getMessage());
            debugCfg = null;
        }
    }

    public static int configVersion() {
        return cfg.getInt("config-version", 0);
    }

    public static String getLanguage() {
        return cfg.getString("language", "en");
    }

    public static boolean isDebug() {
        if (cfg != null && cfg.getBoolean("debug", false)) return true;
        return debugCfg != null && debugCfg.getBoolean("enabled", false);
    }

    public static boolean debugDbConn() {
        return isDebug() || debugDatabase() || debugBool("database.connection", false);
    }

    public static boolean debugDbOps() {
        return isDebug() || debugDatabase() || debugBool("database.operations", false);
    }

    public static boolean debugNmsBot() {
        if (isDebug()) return true;
        if (debugBool("nms.bot", false)) return true;
        return false;
    }

    public static boolean debugNmsConn() {
        if (isDebug()) return true;
        if (debugBool("nms.connection", false)) return true;
        return false;
    }

    public static boolean debugNmsDamage() {
        if (isDebug()) return true;
        if (debugNms()) return true;
        return debugBool("nms.damage", false);
    }

    public static boolean debugStartup() {
        return isDebug() || debugBool("startup", false);
    }

    public static boolean debugNms() {
        if (isDebug()) return true;
        return debugBool("nms.enabled", false);
    }

    public static boolean debugPackets() {
        return isDebug() || debugBool("packets", false);
    }

    public static boolean debugNetwork() {
        return isDebug() || debugBool("network", false);
    }

    public static boolean debugConfigSync() {
        return isDebug() || debugBool("config-sync", false);
    }

    public static boolean debugSkin() {
        return isDebug() || debugBool("nms.skin", false);
    }

    public static boolean debugDatabase() {
        return isDebug() || debugBool("database.enabled", false);
    }

    public static boolean debugChat() {
        return isDebug() || debugBool("chat", false);
    }

    public static boolean debugPathfinding() {
        return isDebug() || debugBool("pathfinding", false);
    }

    public static boolean debugSkinPool() {
        return isDebug() || debugBool("skin-pool", false);
    }

    public static boolean debugRental() {
        return isDebug() || debugBool("rental", false);
    }

    public static boolean debugAuth() {
        return isDebug() || debugBool("auth", false);
    }

    public static boolean debugCommands() {
        return isDebug() || debugBool("commands", false);
    }

    public static boolean debugHeadAi() {
        return isDebug() || debugBool("head-ai", false);
    }

    public static boolean debugRightClick() {
        return isDebug() || debugBool("right-click", false);
    }

    public static boolean debugRightClickHead() {
        return isDebug() || debugBool("right-click-head", false);
    }

    public static boolean debugLeftClick() {
        return isDebug() || debugBool("left-click", false);
    }

    public static boolean debugLeftClickHead() {
        return isDebug() || debugBool("left-click-head", false);
    }

    public static boolean debugGeneral() {
        return isDebug() || debugBool("general", false);
    }

    public static boolean debugNmsPhysics() {
        if (isDebug()) return true;
        if (debugBool("nms.physics", false)) return true;
        return false;
    }

    public static boolean debugDbMigration() {
        return isDebug() || debugDatabase() || debugBool("database.migration", false);
    }

    public static boolean debugDbPersistence() {
        return isDebug() || debugDatabase() || debugBool("database.persistence", false);
    }

    public static boolean debugChatBroadcast() {
        return isDebug() || debugBool("debug-chat", false);
    }

    public static void setDebugBool(String path, boolean value) {
        if (debugCfg == null) return;
        debugCfg.set(path, value);
        saveDebugConfig();
    }

    private static void saveDebugConfig() {
        if (debugCfg == null) return;
        try {
            java.io.File debugFile = new java.io.File(plugin.getDataFolder(), "debug.yml");
            debugCfg.save(debugFile);
        } catch (Exception e) {
            FppLogger.warn("Failed to save debug.yml: " + e.getMessage());
        }
    }

    /**
     * Download new releases from Modrinth into {@code plugins/update} so they install on the next
     * restart. On by default; set {@code update-checker.auto-update: false} to only be notified.
     */
    public static boolean updateCheckerAutoUpdate() {
        return cfg.getBoolean("update-checker.auto-update", true);
    }

    public static boolean updateCheckerEnabled() {
        return cfg.getBoolean("update-checker.enabled", true);
    }

    /**
     * Help display mode. "gui" (default) opens the HelpGui chest for players; "text" always uses
     * the paginated chat renderer. Controlled by the "help.mode" config key.
     */
    public static String helpMode() {
        return cfg.getString("help.mode", "gui").toLowerCase();
    }

    public static boolean metricsEnabled() {
        return cfg.getBoolean("metrics.enabled", true);
    }

    public static boolean metricsDebug() {
        return cfg.getBoolean("metrics.debug", false);
    }

    public static boolean heartbeatEnabled() {
        return cfg.getBoolean("heartbeat.enabled", true);
    }

    public static int spawnCooldown() {
        return Math.max(0, cfg.getInt("spawn-cooldown", 0));
    }

    /**
     * Admin toggle ({@code tab-list.enabled}, default off). When on, bots appear in the tab list,
     * but only ever as {@code [Bot] - <botname>} (see {@link FakePlayer#tabListName}), never under a
     * bare name that could pass for a real player.
     */
    public static boolean tabListEnabled() {
        return cfg.getBoolean("tab-list.enabled", false);
    }

    public static int maxBots() {
        return cfg.getInt("limits.max-bots", 1000);
    }

    public static int userBotLimit() {
        return cfg.getInt("limits.user-bot-limit", 1);
    }

    // ── Economy / bot rental ────────────────────────────────────────────────────────────────────

    public static boolean economyEnabled() {
        return cfg.getBoolean("economy.enabled", false);
    }

    /** {@code auto} | {@code vault} | {@code excellenteconomy} | {@code none}. */
    public static String economyProvider() {
        return cfg.getString("economy.provider", "auto");
    }

    /** Currency id to charge in ExcellentEconomy (ignored for Vault, which is single-currency). */
    public static String excellentEconomyCurrencyId() {
        return cfg.getString("economy.excellent-economy-currency-id", "money");
    }

    public static double rentalPricePerHour() {
        return Math.max(0.0, cfg.getDouble("economy.rental.price-per-hour", 100.0));
    }

    /** One-time charge for renting a brand-new bot slot, on top of the per-hour time cost. */
    public static double rentalPricePerBotSlot() {
        return Math.max(0.0, cfg.getDouble("economy.rental.price-per-bot-slot", 0.0));
    }

    public static int rentalMinHours() {
        return Math.max(1, cfg.getInt("economy.rental.min-hours", 1));
    }

    public static int rentalMaxHours() {
        return Math.max(rentalMinHours(), cfg.getInt("economy.rental.max-hours", 72));
    }

    /** Maximum hours a single rented bot may have banked at once, across all extensions. */
    public static int rentalMaxBankedHours() {
        return Math.max(rentalMaxHours(), cfg.getInt("economy.rental.max-banked-hours", 168));
    }

    public static int rentalWarnMinutesBeforeExpiry() {
        return Math.max(0, cfg.getInt("economy.rental.warn-minutes-before-expiry", 10));
    }

    public static int rentalSweepIntervalSeconds() {
        return Math.max(5, cfg.getInt("economy.rental.sweep-interval-seconds", 30));
    }

    /** How many rented (paid) bots one player may have active at once; unaffected by fpp.rent.unlimited. */
    public static int rentalMaxBotsPerPlayer() {
        return Math.max(1, cfg.getInt("economy.rental.max-bots-per-player", 3));
    }

    public static String adminBotNameFormat() {
        return cfg.getString("bot-name.admin-format", "{bot_name}");
    }

    public static String userBotNameFormat() {
        return cfg.getString("bot-name.user-format", "<gray>[bot-{spawner}-{num}]</gray>");
    }

    /**
     * The disclosure line ("bot by {owner}") is mandatory and cannot be disabled - every bot must
     * always visibly identify itself as a bot, never as an indistinguishable real player.
     */
    public static boolean nametagSecondLineEnabled() {
        return true;
    }

    public static String nametagSecondLineFormat() {
        String fmt = string("nametag.second-line.format", "<#BAFF4F>bot by {owner}</#BAFF4F>");
        // Older configs persisted previous gray defaults (hard to read against the sky); those
        // untouched defaults are upgraded to the current lime color. Custom formats are kept.
        String compact = fmt.replace(" ", "").toLowerCase(java.util.Locale.ROOT);
        if (compact.equals("<#9691ab>ʙᴏᴛʙʏ{owner}</#9691ab>")
                || compact.equals("<gray>botby{owner}</gray>")
                || compact.equals("<gray>ʙᴏᴛʙʏ{owner}</gray>")
                || compact.equals("<#9691ab>botby{owner}</#9691ab>")
                || compact.equals("<dark_gray>botby{owner}</dark_gray>")
                || compact.equals("<dark_gray>ʙᴏᴛʙʏ{owner}</dark_gray>")) {
            return "<#BAFF4F>ʙᴏᴛ ʙʏ {owner}</#BAFF4F>";
        }
        return fmt;
    }

    public static double nametagSecondLineYOffset() {
        return cfg.getDouble("nametag.second-line.y-offset", 1.9);
    }

    /** Background plate alpha (0-255) behind the tag text; vanilla name tags use 25% black = 64. */
    public static int nametagBackgroundAlpha() {
        double opacity = cfg.getDouble("nametag.second-line.background-opacity", 0.25);
        return (int) Math.round(Math.max(0.0, Math.min(1.0, opacity)) * 255.0);
    }

    /** Vertical gap between the stacked tag rows (blocks); 0.3 leaves a thin gap between plates. */
    public static double nametagRowSpacing() {
        return Math.max(0.2, cfg.getDouble("nametag.second-line.row-spacing", 0.3));
    }

    public static int nametagInterpolationTicks() {
        return integer("nametag.second-line.interpolation-ticks", 3);
    }

    public static boolean skinRarePoolsEnabled() {
        return cfg.getBoolean("skin.rare-pools", true);
    }

    public static String skinMineSkinApiKey() {
        return cfg.getString("skin.mineskin-api-key", "");
    }

    public static boolean bodyPushable() {
        return cfg.getBoolean("body.pushable", true);
    }

    public static boolean bodyDamageable() {
        return cfg.getBoolean("body.damageable", true);
    }

    public static boolean bodyPickUpItems() {
        return cfg.getBoolean("body.pick-up-items", false);
    }

    /**
     * Boats and moving rideable minecarts that run into a bot pick it up, exactly as vanilla vehicles
     * pick up villagers and other mobs. Admin toggle, on by default.
     */
    public static boolean autoMountEnabled() {
        return cfg.getBoolean("body.auto-mount", true);
    }

    public static boolean bodyPickUpXp() {
        return cfg.getBoolean("body.pick-up-xp", true);
    }

    public static boolean autoEatEnabled() {
        return cfg.getBoolean("automation.auto-eat", true);
    }

    /** Default hunger level (0-19) at or below which a bot auto-eats. Clamped to a valid range. */
    public static int autoEatHungerThreshold() {
        return Math.max(0, Math.min(19, cfg.getInt("automation.auto-eat-threshold", 17)));
    }

    public static boolean autoPlaceBedEnabled() {
        return cfg.getBoolean("automation.auto-place-bed", true);
    }

    public static boolean autoMilkEnabled() {
        return cfg.getBoolean("automation.auto-milk", true);
    }

    public static boolean preventBadOmen() {
        return cfg.getBoolean("automation.prevent-bad-omen", true);
    }

    public static boolean dropItemsOnDespawn() {
        return cfg.getBoolean("body.drop-items-on-despawn", false);
    }

    /** Whether left-click mining auto-equips the best available tool for the target block first. */
    public static boolean autoToolSwitchEnabled() {
        return cfg.getBoolean("left-click.auto-tool-switch", true);
    }

    public static boolean persistOnRestart() {
        return cfg.getBoolean("persistence.enabled", true);
    }

    public static int restoreDelayTicks() {
        return Math.max(0, cfg.getInt("persistence.restore-delay-ticks", 0));
    }

    public static int restoreBatchSize() {
        return Math.max(1, cfg.getInt("persistence.restore-batch-size", 1));
    }

    public static List<String> namePool() {
        return BotNameConfig.getNames();
    }

    public static boolean joinMessage() {
        return cfg.getBoolean("messages.join-message", true);
    }

    public static boolean leaveMessage() {
        return cfg.getBoolean("messages.leave-message", true);
    }

    public static boolean warningsNotifyAdmins() {
        return cfg.getBoolean("messages.notify-admins-on-join", true);
    }

    public static double maxHealth() {
        return cfg.getDouble("combat.max-health", 20.0);
    }

    public static boolean hurtSound() {
        return cfg.getBoolean("combat.hurt-sound", true);
    }

    public static boolean fallDamageEnabled() {
        return cfg.getBoolean("combat.fall-damage.enabled", true);
    }

    public static double fallDamageSafeDistance() {
        return Math.max(3.0, cfg.getDouble("combat.fall-damage.safe-distance", 3.0));
    }

    public static double fallDamageMultiplier() {
        return Math.max(0.0, cfg.getDouble("combat.fall-damage.multiplier", 1.0));
    }

    public static boolean respawnOnDeath() {
        return cfg.getBoolean("death.respawn-on-death", false);
    }

    public static int respawnDelay() {
        return cfg.getInt("death.respawn-delay", 60);
    }

    public static boolean suppressDrops() {
        return cfg.getBoolean("death.suppress-drops", false);
    }

    public static boolean chunkLoadingEnabled() {
        return cfg.getBoolean("chunk-loading.enabled", true);
    }

    public static int chunkLoadingRadius() {
        Object raw = cfg.get("chunk-loading.radius");
        if (raw instanceof Number n) {
            return Math.max(0, n.intValue());
        }

        return Bukkit.getSimulationDistance();
    }

    public static int chunkLoadingUpdateInterval() {
        return cfg.getInt("chunk-loading.update-interval", 20);
    }

    public static int chunkLoadingMassDisableThreshold() {
        return cfg.getInt("chunk-loading.mass-disable-threshold", 100);
    }

    public static boolean headAiEnabled() {
        return cfg.getBoolean("head-ai.enabled", true);
    }

    public static double headAiLookRange() {
        return cfg.getDouble("head-ai.look-range", 8.0);
    }

    public static float headAiTurnSpeed() {
        return (float) cfg.getDouble("head-ai.turn-speed", 0.3);
    }

    public static int headAiTickRate() {
        return Math.max(1, cfg.getInt("head-ai.tick-rate", 3));
    }

    public static boolean swimAiEnabled() {
        return cfg.getBoolean("swim-ai.enabled", true);
    }

    public static boolean pathfindingParkour() {
        return bool("pathfinding.parkour", false);
    }

    public static boolean pathfindingBreakBlocks() {
        return bool("pathfinding.break-blocks", false);
    }

    public static boolean pathfindingPlaceBlocks() {
        return bool("pathfinding.place-blocks", false);
    }

    public static String pathfindingPlaceMaterial() {
        return string("pathfinding.place-material", "DIRT");
    }

    public static double pathfindingArrivalDistance() {
        return decimal("pathfinding.arrival-distance", 1.2);
    }

    public static double pathfindingPatrolArrivalDistance() {
        return decimal("pathfinding.patrol-arrival-distance", 1.5);
    }

    public static double pathfindingWaypointArrivalDistance() {
        return decimal("pathfinding.waypoint-arrival-distance", 0.65);
    }

    public static double pathfindingSprintDistance() {
        return decimal("pathfinding.sprint-distance", 6.0);
    }

    public static double pathfindingFollowRecalcDistance() {
        return decimal("pathfinding.follow-recalc-distance", 3.5);
    }

    public static int pathfindingFollowRecalcInterval() {
        return Math.max(1, integer("pathfinding.follow-recalc-interval", 100));
    }

    public static int pathfindingRecalcInterval() {
        return Math.max(1, integer("pathfinding.recalc-interval", 60));
    }

    public static int pathfindingStuckTicks() {
        return Math.max(1, integer("pathfinding.stuck-ticks", 10));
    }

    public static double pathfindingStuckThreshold() {
        return Math.max(0.001, decimal("pathfinding.stuck-threshold", 0.04));
    }

    /**
     * How many consecutive stuck→recalculate cycles are tolerated (with zero real progress toward
     * the goal in between) before the navigation is abandoned outright as unreachable, instead of
     * recalculating against the same obstruction forever.
     */
    public static int pathfindingMaxStuckCycles() {
        return Math.max(1, integer("pathfinding.max-stuck-cycles", 4));
    }

    public static int pathfindingBreakTicks() {
        return Math.max(1, integer("pathfinding.break-ticks", 15));
    }

    public static int pathfindingPlaceTicks() {
        return Math.max(1, integer("pathfinding.place-ticks", 5));
    }

    public static int pathfindingMaxFall() {
        return Math.max(1, Math.min(integer("pathfinding.max-fall", 3), 16));
    }

    public static int pathfindingMaxRange() {
        return Math.max(8, integer("pathfinding.max-range", 64));
    }

    public static int pathfindingMaxNodes() {
        return Math.max(100, integer("pathfinding.max-nodes", 900));
    }

    public static int pathfindingMaxNodesExtended() {
        return Math.max(pathfindingMaxNodes(), integer("pathfinding.max-nodes-extended", 1800));
    }

    public static double collisionWalkRadius() {
        return cfg.getDouble("collision.walk-radius", 0.85);
    }

    public static double collisionWalkStrength() {
        return cfg.getDouble("collision.walk-strength", 0.22);
    }

    public static double collisionMaxHoriz() {
        return cfg.getDouble("collision.max-horizontal-speed", 0.30);
    }

    public static double collisionHitStrength() {
        return cfg.getDouble("collision.hit-strength", 0.45);
    }

    public static double collisionHitMaxHoriz() {
        return cfg.getDouble("collision.hit-max-horizontal-speed", 0.80);
    }

    public static double collisionBotRadius() {
        return cfg.getDouble("collision.bot-radius", 0.90);
    }

    public static double collisionBotStrength() {
        return cfg.getDouble("collision.bot-strength", 0.14);
    }

    public static boolean mysqlEnabled() {
        return cfg.getBoolean("database.mysql-enabled", false);
    }

    public static boolean databaseEnabled() {
        return cfg.getBoolean("database.enabled", true);
    }

    public static String databaseMode() {
        String raw = cfg.getString("database.mode", "LOCAL");
        return raw.trim().equalsIgnoreCase("NETWORK") ? "NETWORK" : "LOCAL";
    }

    public static boolean isNetworkMode() {
        return databaseEnabled() && databaseMode().equalsIgnoreCase("NETWORK");
    }

    public static String configSyncMode() {
        String raw = cfg.getString("config-sync.mode", "DISABLED");
        return raw.trim().toUpperCase();
    }

    public static String mysqlHost() {
        return cfg.getString("database.mysql.host", "localhost");
    }

    public static int mysqlPort() {
        return cfg.getInt("database.mysql.port", 3306);
    }

    public static String mysqlDatabase() {
        return cfg.getString("database.mysql.database", "fpp");
    }

    public static String mysqlUsername() {
        return cfg.getString("database.mysql.username", "root");
    }

    public static String mysqlPassword() {
        return cfg.getString("database.mysql.password", "");
    }

    public static boolean mysqlUseSSL() {
        return cfg.getBoolean("database.mysql.use-ssl", false);
    }

    public static int mysqlPoolSize() {
        return cfg.getInt("database.mysql.pool-size", 5);
    }

    public static int mysqlConnTimeout() {
        return cfg.getInt("database.mysql.connection-timeout", 30000);
    }

    public static int dbLocationFlushInterval() {
        return cfg.getInt("database.location-flush-interval", 30);
    }

    public static int dbMaxHistoryRows() {
        return cfg.getInt("database.session-history.max-rows", 20);
    }

    public static String serverId() {

        String id = cfg.getString("database.server-id", null);

        if (id == null || id.isBlank()) {
            id = cfg.getString("server.id", "default");
        }
        return (id == null || id.isBlank()) ? "default" : id.trim();
    }

    public static double positionSyncDistance() {
        return cfg.getDouble("performance.position-sync-distance", 128.0);
    }

    public static boolean isBadwordFilterEnabled() {
        return cfg.getBoolean("badword-filter.enabled", true);
    }

    public static List<String> getBadwords() {
        Object raw = cfg.get("badword-filter.words");
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(o -> o instanceof String)
                    .map(o -> (String) o)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        return List.of();
    }

    public static boolean isBadwordGlobalListEnabled() {
        return cfg.getBoolean("badword-filter.use-global-list", true);
    }

    public static String badwordGlobalListUrl() {
        return cfg.getString(
                "badword-filter.global-list-url", "https://www.cs.cmu.edu/~biglou/resources/bad-words.txt");
    }

    public static int badwordGlobalListTimeoutMs() {
        return Math.max(1000, cfg.getInt("badword-filter.global-list-timeout-ms", 5000));
    }

    public static List<String> getBadwordWhitelist() {
        Object raw = cfg.get("badword-filter.whitelist");
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(o -> o instanceof String)
                    .map(o -> (String) o)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        return List.of();
    }

    public static boolean isBadwordAutoRenameEnabled() {
        return cfg.getBoolean("badword-filter.auto-rename", true);
    }

    public static boolean isBadwordAutoDetectionEnabled() {
        return cfg.getBoolean("badword-filter.auto-detection.enabled", true);
    }

    public static String getBadwordAutoDetectionMode() {
        return cfg.getString("badword-filter.auto-detection.mode", "normal").toLowerCase();
    }

    public static boolean isBotRightClickEnabled() {
        return cfg.getBoolean("bot-interaction.right-click-enabled", true);
    }

    public static boolean isBotShiftRightClickSettingsEnabled() {
        return cfg.getBoolean("bot-interaction.shift-right-click-settings", true);
    }

    public static void debug(String message) {
        FppLogger.debug(message);
    }

    public static void debugStartup(String message) {
        FppLogger.debug("STARTUP", debugStartup(), message);
    }

    public static void debugNms(String message) {
        FppLogger.debug("NMS", debugNms(), message);
    }

    public static void debugPackets(String message) {
        FppLogger.debug("PACKETS", debugPackets(), message);
    }

    public static void debugNetwork(String message) {
        FppLogger.debug("NETWORK", debugNetwork(), message);
    }

    public static void debugConfigSync(String message) {
        FppLogger.debug("CONFIG_SYNC", debugConfigSync(), message);
    }

    public static void debugSkin(String message) {
        FppLogger.debug("SKIN", debugSkin(), message);
    }

    public static void debugDatabase(String message) {
        FppLogger.debug("DATABASE", debugDatabase(), message);
    }

    public static void debugDbConn(String message) {
        FppLogger.debug("DB-CONN", debugDbConn(), message);
    }

    public static void debugDbOps(String message) {
        FppLogger.debug("DB-OPS", debugDbOps(), message);
    }

    public static void debugNmsBot(String message) {
        FppLogger.debug("NMS-BOT", debugNmsBot(), message);
    }

    public static void debugNmsConn(String message) {
        FppLogger.debug("NMS-CONN", debugNmsConn(), message);
    }

    public static void debugNmsDamage(String message) {
        FppLogger.debug("NMS-DAMAGE", debugNmsDamage(), message);
    }

    public static void debugChat(String message) {
        FppLogger.debug("CHAT", debugChat(), message);
    }

    public static void debugPathfinding(String message) {
        FppLogger.debug("PATHFINDING", debugPathfinding(), message);
    }

    public static void debugSkinPool(String message) {
        FppLogger.debug("SKIN-POOL", debugSkinPool(), message);
    }

    public static void debugRental(String message) {
        FppLogger.debug("RENTAL", debugRental(), message);
    }

    /** Server-wide default ticks between block breaks for held left-click mining (REPEAT/HOLD). Per-bot overridable. */
    public static int leftClickIntervalTicks() {
        return Math.max(1, cfg.getInt("left-click.interval-ticks", 4));
    }

    /** Server-wide default ticks between held right-click pulses. Per-bot overridable. */
    public static int rightClickIntervalTicks() {
        return Math.max(1, cfg.getInt("right-click.interval-ticks", 4));
    }

    public static double attackMobDefaultRange() {
        return cfg.getDouble("attack-mob.default-range", 8.0);
    }

    public static String attackMobDefaultPriority() {
        return cfg.getString("attack-mob.default-priority", "nearest");
    }

    public static boolean performanceEnabled() {
        return cfg.getBoolean("performance.enabled", true);
    }

    public static boolean performanceSparkEnabled() {
        return cfg.getBoolean("performance.spark-enabled", true);
    }

    public static boolean performancePlaceholdersEnabled() {
        return cfg.getBoolean("performance.placeholders", true);
    }

    public static int performanceSampleIntervalTicks() {
        return Math.max(1, cfg.getInt("performance.sample-interval-ticks", 20));
    }

    public static int performanceHistoryMinutes() {
        return Math.max(1, cfg.getInt("performance.history-minutes", 15));
    }

    public static double performanceWarnMspt() {
        return cfg.getDouble("performance.warn-mspt", 60.0);
    }

    public static double performanceWarnTps() {
        return cfg.getDouble("performance.warn-tps", 18.0);
    }

    public static int performanceWarnConsecutiveSamples() {
        return Math.max(1, cfg.getInt("performance.warn-consecutive-samples", 3));
    }

    public static int performanceWarnCooldownMinutes() {
        return Math.max(0, cfg.getInt("performance.warn-cooldown-minutes", 5));
    }

    public static int performanceAutoProfilerTimeoutSeconds() {
        return Math.max(10, cfg.getInt("performance.auto-profiler-timeout-seconds", 60));
    }

    public static boolean performanceSelfProfilerEnabled() {
        return cfg.getBoolean("performance.self-profiler.enabled", false);
    }

    public static boolean performanceSelfProfilerMethodLevel() {
        return cfg.getBoolean("performance.self-profiler.method-level", false);
    }

    public static boolean performanceSelfProfilerExportOnWarning() {
        return cfg.getBoolean("performance.self-profiler.export-on-warning", true);
    }

    // ── auth (register/login against an installed login plugin) ────────────────────────────────

    public static boolean authEnabled() {
        return cfg.getBoolean("auth.enabled", false);
    }

    public static String authRegisterCommand() {
        return cfg.getString("auth.register-command", "register %password% %password%");
    }

    public static String authLoginCommand() {
        return cfg.getString("auth.login-command", "login %password%");
    }

    public static int authDelayMinTicks() {
        return Math.max(0, cfg.getInt("auth.delay-min-ticks", 20));
    }

    public static int authDelayMaxTicks() {
        return Math.max(authDelayMinTicks(), cfg.getInt("auth.delay-max-ticks", 60));
    }

    /** Hard cap on how long a bot stays "frozen" (see FakePlayer#isAuthPending) waiting to detect its own register/login outcome, after which it's released regardless - a safety net for when the login plugin's response can't be read/matched at all. */
    public static int authPendingTimeoutTicks() {
        return Math.max(0, cfg.getInt("auth.pending-timeout-ticks", 100));
    }

    public static int authPasswordLength() {
        return Math.max(8, cfg.getInt("auth.password.length", 12));
    }

    public static boolean authPasswordUppercase() {
        return cfg.getBoolean("auth.password.uppercase", true);
    }

    public static boolean authPasswordLowercase() {
        return cfg.getBoolean("auth.password.lowercase", true);
    }

    public static boolean authPasswordDigits() {
        return cfg.getBoolean("auth.password.digits", true);
    }

    public static boolean authPasswordSymbols() {
        return cfg.getBoolean("auth.password.symbols", true);
    }

    /** Live-writes {@code path} into config.yml's in-memory copy - call {@link #save()} to persist it to disk. Used by {@code /fpp auth on|off} to flip auth.enabled without a full config reload. */
    public static void set(String path, Object value) {
        cfg.set(path, value);
    }

    public static void save() {
        if (plugin != null) plugin.saveConfig();
    }
}
