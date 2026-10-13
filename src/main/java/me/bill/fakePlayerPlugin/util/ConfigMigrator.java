package me.bill.fakePlayerPlugin.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;

public final class ConfigMigrator {

    public static final int CURRENT_VERSION = 79;

    private static boolean rawDebug = false;

    private ConfigMigrator() {}

    public static boolean migrateIfNeeded(FakePlayerPlugin plugin) {
        File configFile = new File(plugin.getDataFolder(), "config.yml");

        if (!configFile.exists()) {
            return false;
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);

        rawDebug = cfg.getBoolean("debug", false) || cfg.getBoolean("logging.debug.startup", false);

        int stored = cfg.getInt("config-version", -1);

        if (stored < 0) {
            FppLogger.warn("═══════════════════════════════════════════════════════════════════");
            FppLogger.warn("  ⚠  FPP: config.yml has no 'config-version' key (unknown legacy)  ⚠");
            FppLogger.warn("  All migration steps will be applied to bring it up to date.");
            FppLogger.warn("  A backup is being created before any changes are written.");
            FppLogger.warn("═══════════════════════════════════════════════════════════════════");
            stored = 0;
        }

        if (stored >= CURRENT_VERSION) {
            if (syncCurrentDefaults(plugin, configFile, cfg)) {
                log("config is current (v" + stored + ") but missing defaults were synced.");
                return true;
            }
            log("config is current (v" + stored + "). No migration needed.");
            return false;
        }

        String fromLabel = stored == 0 ? "legacy/unknown" : "v" + stored;

        FppLogger.info(
                "Config migration starting: " + fromLabel + " → v" + CURRENT_VERSION + ". Creating backup first…");
        if (rawDebug) {
            FppLogger.section("Config Migration");
            FppLogger.info("Upgrading config from " + fromLabel + " → v" + CURRENT_VERSION + "…");
            FppLogger.info("A backup will be created before any changes are written.");
        }
        BackupManager.createFullBackup(plugin, "pre-migration-" + fromLabel, rawDebug);

        boolean anyChange = false;
        if (stored < 2) anyChange |= v1to2(cfg);
        if (stored < 3) anyChange |= v2to3(cfg);
        if (stored < 4) anyChange |= v3to4(cfg);
        if (stored < 5) anyChange |= v4to5(cfg);
        if (stored < 6) anyChange |= v5to6(cfg);
        if (stored < 7) anyChange |= v6to7(cfg);
        if (stored < 8) anyChange |= v7to8(cfg);
        if (stored < 9) anyChange |= v8to9(cfg);
        if (stored < 10) anyChange |= v9to10(cfg);
        if (stored < 11) anyChange |= v10to11(cfg);
        if (stored < 12) anyChange |= v11to12(cfg);
        if (stored < 13) anyChange |= v12to13(cfg);
        if (stored < 14) anyChange |= v13to14(cfg);
        if (stored < 15) anyChange |= v14to15(cfg);
        if (stored < 16) anyChange |= v15to16(cfg);
        if (stored < 17) anyChange |= v16to17(cfg);
        if (stored < 18) anyChange |= v17to18(cfg);
        if (stored < 19) anyChange |= v18to19(cfg);
        if (stored < 20) anyChange |= v19to20(cfg);

        if (stored < 21) anyChange |= v20to21(cfg);
        if (stored < 22) anyChange |= v21to22(cfg);
        if (stored < 23) anyChange |= v22to23(cfg);
        if (stored < 24) anyChange |= v23to24(cfg);
        if (stored < 25) anyChange |= v24to25(cfg);
        if (stored < 26) anyChange |= v25to26(cfg);
        if (stored < 27) anyChange |= v26to27(cfg);
        if (stored < 28) anyChange |= v27to28(cfg);

        if (stored < 29) anyChange |= v28to29(cfg);
        if (stored < 30) anyChange |= v29to30(cfg);
        if (stored < 31) anyChange |= v30to31(cfg);
        if (stored < 32) anyChange |= v31to32(cfg);
        if (stored < 33) anyChange |= v32to33(cfg);
        if (stored < 34) anyChange |= v33to34(cfg);
        if (stored < 35) anyChange |= v34to35(cfg);
        if (stored < 36) anyChange |= v35to36(cfg);
        if (stored < 37) anyChange |= v36to37(cfg);
        if (stored < 38) anyChange |= v37to38(cfg);
        if (stored < 39) anyChange |= v38to39(cfg);
        if (stored < 40) anyChange |= v39to40(cfg);
        if (stored < 41) anyChange |= v40to41(cfg);
        if (stored < 42) anyChange |= v41to42(cfg);
        if (stored < 43) anyChange |= v42to43(cfg);
        if (stored < 44) anyChange |= v43to44(cfg);

        if (stored < 45) anyChange |= v44to45(cfg);
        if (stored < 46) anyChange |= v45to46(cfg);
        if (stored < 47) anyChange |= v46to47(cfg);
        if (stored < 48) anyChange |= v47to48(cfg);
        if (stored < 49) anyChange |= v48to49(cfg);
        if (stored < 50) anyChange |= v49to50(cfg);
        if (stored < 51) anyChange |= v50to51(cfg);
        if (stored < 52) anyChange |= v51to52(cfg);
        if (stored < 53) anyChange |= v52to53(cfg);
        if (stored < 54) anyChange |= v53to54(cfg);
        if (stored < 55) anyChange |= v54to55(cfg);
        if (stored < 56) anyChange |= v55to56(cfg);
        if (stored < 57) anyChange |= v56to57(cfg);
        if (stored < 58) anyChange |= v57to58(cfg);
        if (stored < 59) anyChange |= v58to59(cfg);
        if (stored < 60) anyChange |= v59to60(cfg);
        if (stored < 61) anyChange |= v60to61(cfg);
        if (stored < 62) anyChange |= v61to62(cfg);
        if (stored < 63) anyChange |= v62to63(cfg);
        if (stored < 64) anyChange |= v63to64(cfg);
        if (stored < 65) anyChange |= v64to65(cfg);
        if (stored < 67) anyChange |= v66to67(cfg);
        if (stored < 68) anyChange |= v67to68(cfg);
        if (stored < 69) anyChange |= v68to69(cfg);
        if (stored < 70) anyChange |= v69to70(cfg);
        if (stored < 71) anyChange |= v70to71(cfg);
        if (stored < 72) anyChange |= v71to72(plugin, cfg);
        if (stored < 73) anyChange |= v72to73(cfg);
        if (stored < 74) anyChange |= v73to74(cfg);
        if (stored < 75) anyChange |= v74to75(cfg);
        if (stored < 76) anyChange |= v75to76(cfg);
        if (stored < 77) anyChange |= v76to77(cfg);
        if (stored < 78) anyChange |= v77to78(cfg);
        if (stored < 79) anyChange |= v78to79(cfg);

        fillDefaults(plugin, cfg);

        cfg.set("config-version", CURRENT_VERSION);

        try {
            cfg.save(configFile);
            if (anyChange) {
                if (rawDebug) {
                    FppLogger.success("Config migrated to v" + CURRENT_VERSION + " successfully.");
                } else {
                    FppLogger.info("Config migration applied (v" + fromLabel + " → v" + CURRENT_VERSION + ").");
                }
            } else {
                if (rawDebug) {
                    FppLogger.success("Config stamped as v"
                            + CURRENT_VERSION
                            + " (no structural changes needed; defaults filled).");
                }
            }
        } catch (IOException e) {
            FppLogger.error("ConfigMigrator: failed to save migrated config: " + e.getMessage());
            return false;
        }

        return true;
    }





    private static boolean v1to2(YamlConfiguration cfg) {
        if (cfg.contains("update-checker")) return false;
        cfg.set("update-checker.enabled", true);
        log("v1→v2", "added update-checker section");
        return true;
    }

    private static boolean v2to3(YamlConfiguration cfg) {
        if (cfg.contains("luckperms")) return false;
        cfg.set("luckperms.use-prefix", true);
        log("v2→v3", "added luckperms section");
        return true;
    }

    private static boolean v3to4(YamlConfiguration cfg) {
        boolean changed = false;

        if (cfg.contains("skin-enabled")) {
            boolean was = cfg.getBoolean("skin-enabled", true);
            cfg.set("skin.mode", was ? "auto" : "off");
            cfg.set("skin-enabled", null);
            log("v3→v4", "skin-enabled → skin.mode=" + (was ? "auto" : "off"));
            changed = true;
        }

        if (cfg.contains("skin.enabled")) {
            boolean was = cfg.getBoolean("skin.enabled", true);
            if (!cfg.contains("skin.mode")) cfg.set("skin.mode", was ? "auto" : "off");
            cfg.set("skin.enabled", null);
            log("v3→v4", "skin.enabled → skin.mode");
            changed = true;
        }

        if (!cfg.contains("skin.mode")) {
            cfg.set("skin.mode", "auto");
            changed = true;
        }

        if (cfg.contains("skin.custom.pool") && !cfg.contains("skin.pool")) {
            cfg.set("skin.pool", cfg.get("skin.custom.pool"));
            log("v3→v4", "skin.custom.pool → skin.pool");
            changed = true;
        }

        if (cfg.contains("skin.custom.by-name") && !cfg.contains("skin.overrides")) {
            cfg.set("skin.overrides", cfg.get("skin.custom.by-name"));
            log("v3→v4", "skin.custom.by-name → skin.overrides");
            changed = true;
        }

        if (!cfg.contains("skin.custom.pool") && !cfg.contains("skin.custom.by-name")) {
            cfg.set("skin.custom", null);
        }

        return changed;
    }

    private static boolean v4to5(YamlConfiguration cfg) {
        if (cfg.contains("body")) return false;
        cfg.set("body.pushable", true);
        cfg.set("body.damageable", true);
        cfg.set("body.pick-up-items", false);
        cfg.set("body.pick-up-xp", false);
        cfg.set("body.drop-items-on-despawn", false);
        cfg.set("spawn-body", null);
        log("v4→v5", "removed spawn-body (bodies always enabled)");
        return true;
    }

    private static boolean v5to6(YamlConfiguration cfg) {
        if (cfg.contains("chunk-loading")) return false;
        cfg.set("chunk-loading.enabled", true);
        cfg.set("chunk-loading.radius", 6);
        cfg.set("chunk-loading.update-interval", 20);
        log("v5→v6", "added chunk-loading section");
        return true;
    }

    private static boolean v6to7(YamlConfiguration cfg) {
        if (cfg.contains("head-ai")) return false;
        cfg.set("head-ai.look-range", 8.0);
        cfg.set("head-ai.turn-speed", 0.3);
        log("v6→v7", "added head-ai section");
        return true;
    }

    private static boolean v7to8(YamlConfiguration cfg) {
        if (cfg.contains("collision")) return false;
        cfg.set("collision.walk-radius", 0.85);
        cfg.set("collision.walk-strength", 0.22);
        cfg.set("collision.max-horizontal-speed", 0.30);
        cfg.set("collision.hit-strength", 0.45);
        cfg.set("collision.bot-radius", 0.90);
        cfg.set("collision.bot-strength", 0.14);
        log("v7→v8", "added collision section");
        return true;
    }

    private static boolean v8to9(YamlConfiguration cfg) {
        log("v8→v9", "extension-owned swap/fake-chat config is no longer added to core config");
        return false;
    }

    private static boolean v9to10(YamlConfiguration cfg) {
        boolean changed = false;

        if (!cfg.contains("limits")) {
            int prev = cfg.getInt("max-bots", 1000);
            cfg.set("limits.max-bots", prev);
            cfg.set("limits.user-bot-limit", 1);
            cfg.set("limits.spawn-presets", Arrays.asList(1, 5, 10, 15, 20));
            cfg.set("max-bots", null);
            log("v9→v10", "max-bots → limits section");
            changed = true;
        } else if (cfg.contains("max-bots")) {

            if (!cfg.contains("limits.max-bots")) cfg.set("limits.max-bots", cfg.getInt("max-bots", 1000));
            cfg.set("max-bots", null);
            changed = true;
        }

        if (!cfg.contains("bot-name")) {
            cfg.set("bot-name.admin-format", "{bot_name}");
            cfg.set("bot-name.user-format", "bot-{spawner}-{num}");
            log("v9→v10", "added bot-name section");
            changed = true;
        }

        if (!cfg.contains("persistence")) {
            boolean prev = cfg.getBoolean("persist-on-restart", true);
            cfg.set("persistence.enabled", prev);
            cfg.set("persist-on-restart", null);
            log("v9→v10", "persist-on-restart → persistence.enabled");
            changed = true;
        }

        if (!cfg.contains("death")) {
            cfg.set("death.respawn-on-death", false);
            cfg.set("death.respawn-delay", 60);
            cfg.set("death.suppress-drops", true);
            log("v9→v10", "added death section");
            changed = true;
        }

        if (!cfg.contains("database")) {
            cfg.set("database.mysql-enabled", false);
            cfg.set("database.mysql.host", "localhost");
            cfg.set("database.mysql.port", 3306);
            cfg.set("database.mysql.database", "fpp");
            cfg.set("database.mysql.username", "root");
            cfg.set("database.mysql.password", "");
            cfg.set("database.mysql.use-ssl", false);
            cfg.set("database.mysql.pool-size", 5);
            cfg.set("database.mysql.connection-timeout", 30000);
            cfg.set("database.location-flush-interval", 30);
            cfg.set("database.session-history.max-rows", 20);
            log("v9→v10", "added database section");
            changed = true;
        }

        return changed;
    }

    private static boolean v10to11(YamlConfiguration cfg) {
        boolean changed = false;
        if (!cfg.contains("skin.guaranteed-skin")) {
            cfg.set("skin.guaranteed-skin", true);
            log("v10→v11", "added skin.guaranteed-skin = true");
            changed = true;
        }
        if (!cfg.contains("skin.fallback-name")) {
            cfg.set("skin.fallback-name", "BotSkin");
            log("v10→v11", "added skin.fallback-name = BotSkin");
            changed = true;
        }
        return changed;
    }

    private static boolean v11to12(YamlConfiguration cfg) {
        if (cfg.contains("head-ai.enabled")) return false;
        cfg.set("head-ai.enabled", true);
        log("v11→v12", "added head-ai.enabled = true");
        return true;
    }

    private static boolean v12to13(YamlConfiguration cfg) {
        if (cfg.contains("metrics.enabled")) return false;
        cfg.set("metrics.enabled", true);
        log("v12→v13", "added metrics.enabled = true");
        return true;
    }

    private static boolean v13to14(YamlConfiguration cfg) {
        boolean changed = false;
        if (!cfg.contains("spawn-cooldown")) {
            cfg.set("spawn-cooldown", 0);
            log("v13→v14", "added spawn-cooldown = 0");
            changed = true;
        }
        return changed;
    }

    private static boolean v14to15(YamlConfiguration cfg) {
        boolean changed = false;
        if (!cfg.contains("database.enabled")) {
            cfg.set("database.enabled", true);
            log("v14→v15", "added database.enabled = true");
            changed = true;
        }
        if (!cfg.contains("messages.notify-admins-on-join")) {
            cfg.set("messages.notify-admins-on-join", true);
            log("v14→v15", "added messages.notify-admins-on-join = true");
            changed = true;
        }
        return changed;
    }

    private static boolean v15to16(YamlConfiguration cfg) {
        boolean changed = false;
        if (!cfg.contains("luckperms.weight-ordering-enabled")) {
            cfg.set("luckperms.weight-ordering-enabled", true);
            log("v15→v16", "added luckperms.weight-ordering-enabled = true");
            changed = true;
        }
        return changed;
    }

    private static boolean v16to17(YamlConfiguration cfg) {
        boolean changed = false;
        if (!cfg.contains("luckperms.bot-group")) {
            cfg.set("luckperms.bot-group", "");
            log("v16→v17", "added luckperms.bot-group = ''");
            changed = true;
        }
        if (!cfg.contains("luckperms.packet-prefix-char")) {
            cfg.set("luckperms.packet-prefix-char", "{");
            log("v16→v17", "added luckperms.packet-prefix-char = '{'");
            changed = true;
        }
        return changed;
    }

    private static boolean v17to18(YamlConfiguration cfg) {
        boolean changed = false;
        if (!cfg.contains("skin.use-skin-folder")) {
            cfg.set("skin.use-skin-folder", true);
            log("v17→v18", "added skin.use-skin-folder = true");
            changed = true;
        }
        if (!cfg.contains("skin.clear-cache-on-reload")) {
            cfg.set("skin.clear-cache-on-reload", true);
            log("v17→v18", "added skin.clear-cache-on-reload = true");
            changed = true;
        }
        return changed;
    }

    private static boolean v18to19(YamlConfiguration cfg) {

        return false;
    }

    private static void fillDefaults(FakePlayerPlugin plugin, YamlConfiguration cfg) {
        try (InputStream stream = plugin.getResource("config.yml")) {
            if (stream == null) return;
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(stream));
            cfg.setDefaults(defaults);
            cfg.options().copyDefaults(true);
        } catch (IOException e) {

            FppLogger.warn("ConfigMigrator.fillDefaults: " + e.getMessage());
        }
    }

    private static boolean syncCurrentDefaults(FakePlayerPlugin plugin, File configFile, YamlConfiguration cfg) {
        String before = cfg.saveToString();
        fillDefaults(plugin, cfg);
        String after = cfg.saveToString();
        if (before.equals(after)) return false;
        try {
            BackupManager.createConfigFilesBackup(plugin, "config-default-sync");
            cfg.save(configFile);
            return true;
        } catch (IOException e) {
            FppLogger.error("ConfigMigrator: failed to save synced config defaults: " + e.getMessage());
            return false;
        }
    }

    private static boolean v19to20(YamlConfiguration cfg) {
        boolean any = false;
        if (!cfg.contains("luckperms.weight-offset")) {
            cfg.set("luckperms.weight-offset", -10);
            log("v19→v20", "added luckperms.weight-offset = -10");
            any = true;
        }
        return any;
    }

    private static boolean v20to21(YamlConfiguration cfg) {
        log("v20→v21", "housekeeping stamp (no structural changes)");
        return false;
    }

    private static boolean v21to22(YamlConfiguration cfg) {
        return false;
    }

    private static boolean v22to23(YamlConfiguration cfg) {
        return false;
    }

    private static boolean v23to24(YamlConfiguration cfg) {
        return false;
    }

    private static boolean v24to25(YamlConfiguration cfg) {

        log("v24→v25", "housekeeping stamp for v1.4.23 (no structural changes)");
        return false;
    }

    private static boolean v25to26(YamlConfiguration cfg) {

        log("v25→v26", "housekeeping stamp for v1.4.24 (no structural changes)");
        return false;
    }

    private static boolean v26to27(YamlConfiguration cfg) {
        if (cfg.contains("bot-name.tab-list-format")) return false;
        cfg.set("bot-name.tab-list-format", "{prefix}{bot_name}{suffix}");
        log("v26→v27", "added bot-name.tab-list-format");
        return true;
    }

    private static boolean v27to28(YamlConfiguration cfg) {
        boolean changed = false;
        if (!cfg.contains("body.pushable")) {
            cfg.set("body.pushable", true);
            log("v27→v28", "added body.pushable = true");
            changed = true;
        }
        if (!cfg.contains("body.damageable")) {
            cfg.set("body.damageable", true);
            log("v27→v28", "added body.damageable = true");
            changed = true;
        }
        return changed;
    }

    private static boolean v28to29(YamlConfiguration cfg) {
        log("v28→v29", "housekeeping stamp (no structural changes)");
        return false;
    }

    private static boolean v29to30(YamlConfiguration cfg) {
        boolean changed = false;
        if (!cfg.contains("database.mode")) {
            cfg.set("database.mode", "LOCAL");
            log("v29→v30", "added database.mode = LOCAL");
            changed = true;
        }
        if (!cfg.contains("server.id")) {
            cfg.set("server.id", "default");
            log("v29→v30", "added server.id = default");
            changed = true;
        }
        return changed;
    }

    private static boolean v30to31(YamlConfiguration cfg) {
        boolean changed = false;

        if (cfg.contains("server.id") && !cfg.contains("database.server-id")) {
            String oldId = cfg.getString("server.id", "default");
            cfg.set("database.server-id", oldId);
            log("v30→v31", "moved server.id → database.server-id = " + oldId);
            changed = true;
        } else if (!cfg.contains("database.server-id")) {
            cfg.set("database.server-id", "default");
            log("v30→v31", "added database.server-id = default");
            changed = true;
        }

        if (cfg.contains("server")) {
            cfg.set("server", null);
            log("v30→v31", "removed unused server: section");
            changed = true;
        }

        return changed;
    }

    private static boolean v31to32(YamlConfiguration cfg) {
        boolean changed = false;

        if (cfg.contains("luckperms.use-prefix")
                || cfg.contains("luckperms.weight-ordering-enabled")
                || cfg.contains("luckperms.bot-group")
                || cfg.contains("luckperms.packet-prefix-char")) {

            String oldBotGroup = cfg.getString("luckperms.bot-group", "");

            cfg.set("luckperms", null);
            log("v31→v32", "removed old luckperms section (weight-ordering, use-prefix, etc.)");

            cfg.set("luckperms.default-group", oldBotGroup);
            log("v31→v32", "migrated luckperms.bot-group → luckperms.default-group = '" + oldBotGroup + "'");

            changed = true;
        }

        String tabFormat = cfg.getString("bot-name.tab-list-format", "");
        if (tabFormat.contains("{prefix}") || tabFormat.contains("{suffix}")) {
            cfg.set("bot-name.tab-list-format", "{bot_name}");
            log("v31→v32", "updated bot-name.tab-list-format to '{bot_name}' (LP handles prefix/suffix" + " natively)");
            changed = true;
        }

        return changed;
    }

    private static boolean v32to33(YamlConfiguration cfg) {
        boolean changed = false;

        String[] loggingKeys = {
            "logging.debug.startup",
            "logging.debug.nms",
            "logging.debug.packets",
            "logging.debug.luckperms",
            "logging.debug.network",
            "logging.debug.config-sync",
            "logging.debug.skin",
            "logging.debug.database"
        };

        for (String key : loggingKeys) {
            if (!cfg.contains(key)) {
                cfg.set(key, false);
                changed = true;
            }
        }

        if (changed) {
            log("v32→v33", "added granular logging.debug.* toggles (all default false)");
        }
        return changed;
    }

    private static boolean v33to34(YamlConfiguration cfg) {
        if (cfg.contains("collision.hit-max-horizontal-speed")) return false;
        cfg.set("collision.hit-max-horizontal-speed", 0.80);
        log("v33→v34", "added collision.hit-max-horizontal-speed (default 0.80)");
        return true;
    }

    private static boolean v34to35(YamlConfiguration cfg) {
        if (cfg.contains("swim-ai")) return false;
        cfg.set("swim-ai.enabled", true);
        log("v34→v35", "added swim-ai.enabled = true");
        return true;
    }

    private static boolean v35to36(YamlConfiguration cfg) {
        boolean changed = false;

        for (String deadKey : new String[] {
            "luckperms.weight-offset",
            "luckperms.weight-ordering-enabled",
            "luckperms.use-prefix",
            "luckperms.packet-prefix-char",
            "luckperms.bot-group"
        }) {
            if (cfg.contains(deadKey)) {
                cfg.set(deadKey, null);
                log("v35→v36", "removed orphaned key: " + deadKey);
                changed = true;
            }
        }

        if (cfg.contains("skin.custom")) {
            cfg.set("skin.custom", null);
            log("v35→v36", "removed leftover skin.custom section");
            changed = true;
        }

        if (cfg.contains("skin.fallback-pool")) {
            cfg.set("skin.fallback-pool", null);
            log("v35→v36", "removed skin.fallback-pool (fallback is now Steve/Alex by default)");
            changed = true;
        }
        if (cfg.contains("skin.fallback-name")) {
            cfg.set("skin.fallback-name", null);
            log("v35→v36", "removed skin.fallback-name (fallback is now Steve/Alex by default)");
            changed = true;
        }

        if (cfg.contains("skin.guaranteed-skin") && cfg.getBoolean("skin.guaranteed-skin", false)) {
            cfg.set("skin.guaranteed-skin", false);
            log("v35→v36", "reset skin.guaranteed-skin to false (new default: Steve/Alex fallback)");
            changed = true;
        }

        if (cfg.contains("server") && cfg.contains("database.server-id")) {
            cfg.set("server", null);
            log("v35→v36", "removed leftover server: section (already in database.server-id)");
            changed = true;
        }

        return changed;
    }

    private static boolean v36to37(YamlConfiguration cfg) {

        log("v36→v37", "version stamp updated to 37 (FPP 1.5.8 - no structural config changes)");
        return false;
    }

    private static boolean v37to38(YamlConfiguration cfg) {
        if (!cfg.contains("bot-name.tab-list-format")) return false;
        cfg.set("bot-name.tab-list-format", null);
        log("v37→v38", "removed bot-name.tab-list-format (key no longer used)");
        return true;
    }

    private static boolean v38to39(YamlConfiguration cfg) {
        log("v38→v39", "fake-chat config now belongs to the fpp-chat extension");
        return false;
    }

    private static boolean v39to40(YamlConfiguration cfg) {
        log("v39→v40", "fake-chat config now belongs to the fpp-chat extension");
        return false;
    }

    private static boolean v40to41(YamlConfiguration cfg) {
        log("v40→v41", "fake-chat config now belongs to the fpp-chat extension");
        return false;
    }

    private static boolean v41to42(YamlConfiguration cfg) {
        log("v41→v42", "peak-hours config now belongs to the fpp-peaks extension");
        return false;
    }

    private static boolean v42to43(YamlConfiguration cfg) {
        log("v42→v43", "peak-hours config now belongs to the fpp-peaks extension");
        return false;
    }

    private static boolean v43to44(YamlConfiguration cfg) {
        log("v43→v44", "peak-hours config now belongs to the fpp-peaks extension");
        return false;
    }

    private static boolean v44to45(YamlConfiguration cfg) {
        log("v44→v45", "housekeeping stamp (no structural changes)");
        return false;
    }

    private static boolean v45to46(YamlConfiguration cfg) {
        boolean changed = false;
        if (!cfg.contains("performance.position-sync-distance")) {
            cfg.set("performance.position-sync-distance", 128.0);
            log("v45→v46", "added performance.position-sync-distance = 128.0");
            changed = true;
        }
        return changed;
    }

    private static boolean v46to47(YamlConfiguration cfg) {
        log("v46→v47", "swap config now belongs to the fpp-swap extension");
        return false;
    }

    private static boolean v47to48(YamlConfiguration cfg) {
        boolean changed = false;
        if (!cfg.contains("body.pick-up-items")) {
            cfg.set("body.pick-up-items", false);
            log("v47→v48", "added body.pick-up-items = false");
            changed = true;
        }
        return changed;
    }

    private static boolean v48to49(YamlConfiguration cfg) {
        boolean changed = false;

        if (!cfg.contains("body.pick-up-xp") || !cfg.getBoolean("body.pick-up-xp", false)) {
            cfg.set("body.pick-up-xp", true);
            log("v48→v49", "set body.pick-up-xp = true");
            changed = true;
        }
        return changed;
    }

    private static boolean v49to50(YamlConfiguration cfg) {
        log("v49→v50", "pathfinding config now belongs to the fpp-pathfinder extension");
        return false;
    }

    private static boolean v50to51(YamlConfiguration cfg) {
        cfg.set("death.suppress-drops", false);
        log("v50→v51", "reset death.suppress-drops → false (bots now drop items on death like real" + " players)");
        return true;
    }

    private static boolean v51to52(YamlConfiguration cfg) {
        log("v51→v52", "fake-chat config now belongs to the fpp-chat extension");
        return false;
    }

    private static boolean v52to53(YamlConfiguration cfg) {
        Object raw = cfg.get("chunk-loading.radius");

        if (raw instanceof Number n && n.intValue() == 0) {
            cfg.set("chunk-loading.radius", "auto");
            log(
                    "v52→v53",
                    "chunk-loading.radius 0 → \"auto\" (0 now means no chunk loading; \"auto\" ="
                            + " server simulation-distance)");
            return true;
        }
        return false;
    }

    private static boolean v53to54(YamlConfiguration cfg) {
        if (cfg.isSet("body.drop-items-on-despawn")) return false;
        cfg.set("body.drop-items-on-despawn", false);
        log("v53→v54", "added body.drop-items-on-despawn (default false)");
        return true;
    }

    private static boolean v54to55(YamlConfiguration cfg) {
        boolean changed = false;
        changed |= setIfMissing(cfg, "automation.auto-eat", true);
        changed |= setIfMissing(cfg, "automation.auto-place-bed", true);
        changed |= setIfMissing(cfg, "swim-ai.enabled", false);
        if (changed) {
            log("v54→v55", "added automation and swim defaults");
        }
        return changed;
    }

    private static boolean v55to56(YamlConfiguration cfg) {
        return false;
    }

    private static boolean v56to57(YamlConfiguration cfg) {
        return false;
    }

    private static boolean v57to58(YamlConfiguration cfg) {

        return false;
    }

    private static boolean v58to59(YamlConfiguration cfg) {
        boolean changed = false;

        String mode = cfg.getString("skin.mode", "").trim().toLowerCase();
        if (mode.isEmpty() || "off".equals(mode) || "none".equals(mode) || "disabled".equals(mode)) {
            cfg.set("skin.mode", "player");
            changed = true;
        }

        if (!cfg.getBoolean("skin.guaranteed-skin", false)) {
            cfg.set("skin.guaranteed-skin", true);
            changed = true;
        }

        if (!cfg.getBoolean("logging.debug.skin", false)) {
            cfg.set("logging.debug.skin", true);
            changed = true;
        }

        if (changed) {
            log(
                    "v58→v59",
                    "enabled default skin-system settings for existing installs (skin.mode=player"
                            + " when disabled, guaranteed-skin=true, logging.debug.skin=true)");
        }
        return changed;
    }

    private static boolean v59to60(YamlConfiguration cfg) {
        boolean changed = false;

        if (cfg.isSet("skin.fallback-pool")) {
            cfg.set("skin.fallback-pool", null);
            changed = true;
        }
        if (cfg.isSet("skin.fallback-name")) {
            cfg.set("skin.fallback-name", null);
            changed = true;
        }
        if (changed) {
            log(
                    "v59→v60",
                    "removed skin.fallback-pool and skin.fallback-name - now hardcoded in"
                            + " SkinManager (1000-player pool)");
        }
        return changed;
    }

    private static boolean v60to61(YamlConfiguration cfg) {
        return false;
    }

    private static boolean v61to62(YamlConfiguration cfg) {
        log("v61→v62", "pathfinding.max-fall now belongs to the fpp-pathfinder extension");
        return false;
    }

    private static boolean v62to63(YamlConfiguration cfg) {
        boolean changed = false;

        if (cfg.isSet("pathfinding.sprint-jump-follow")) {
            cfg.set("pathfinding.sprint-jump-follow", null);
            changed = true;
            log("v62→v63", "removed obsolete pathfinding.sprint-jump-follow");
        }
        return changed;
    }

    private static boolean v63to64(YamlConfiguration cfg) {
        // messages.death-message used to be added here; bots no longer produce any chat events,
        // so the key is gone and this step is intentionally a no-op.
        return false;
    }

    private static boolean v64to65(YamlConfiguration cfg) {
        // Force drop-items-on-despawn to false so bots preserve their inventory/XP on despawn.
        // The old resource default was 'true', which caused silent item loss on /fpp despawn.
        // This migration flips it to false for all existing installs unconditionally.
        if (cfg.getBoolean("body.drop-items-on-despawn", false)) {
            cfg.set("body.drop-items-on-despawn", false);
            log("v64→v65", "set body.drop-items-on-despawn = false (preserve inventory on despawn)");
            return true;
        }
        return false;
    }

    private static boolean v66to67(YamlConfiguration cfg) {
        log("v66→v67", "housekeeping stamp - bot-name.mode default changed to random");
        return false;
    }

    private static boolean v67to68(YamlConfiguration cfg) {
        log("v67→v68", "ping config now belongs to the fpp-ping extension");
        return false;
    }

    private static boolean v68to69(YamlConfiguration cfg) {
        log("v68→v69", "ping config now belongs to the fpp-ping extension");
        return false;
    }

    private static boolean v69to70(YamlConfiguration cfg) {
        log("v69→v70", "ping config now belongs to the fpp-ping extension");
        return false;
    }

    private static boolean v70to71(YamlConfiguration cfg) {
        boolean changed = false;
        for (String key : new String[] {
            "luckperms",
            "fake-chat",
            "chat",
            "bot-chat",
            "bot-messages",
            "skin",
            "ping",
            "peak-hours",
            "peaks",
            "swap",
            "groups",
            "bot-groups",
            "waypoints",
            "waypoint",
            "command",
            "right-click-command",
            "bots-menu",
            "pathfinder"
        }) {
            if (cfg.isSet(key)) {
                cfg.set(key, null);
                changed = true;
            }
        }
        if (changed) {
            log("v70→v71", "removed extension-owned sections from core config");
        }
        return changed;
    }

    private static boolean v71to72(FakePlayerPlugin plugin, YamlConfiguration cfg) {
        try (InputStream stream = plugin.getResource("config.yml")) {
            if (stream == null) return false;
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(stream));
            boolean changed = pruneUnknownKeys(cfg, defaults, "");
            if (changed) {
                log("v71→v72", "removed config keys no longer used by the core plugin");
            }
            return changed;
        } catch (IOException e) {
            FppLogger.warn("ConfigMigrator v71→v72: " + e.getMessage());
            return false;
        }
    }

    private static boolean v72to73(YamlConfiguration cfg) {
        boolean changed = false;
        for (String path : new String[] {
            "join-delay", "join-delay-min", "join-delay-max", "leave-delay", "leave-delay-min", "leave-delay-max"
        }) {
            if (cfg.isSet(path)) {
                cfg.set(path, null);
                changed = true;
            }
        }
        if (changed) {
            log("v72→v73", "removed join/leave delay settings from core config");
        }
        return changed;
    }

    private static boolean v73to74(YamlConfiguration cfg) {
        if (!cfg.contains("logging.debug.license")) {
            cfg.set("logging.debug.license", false);
            log("v73→v74", "added logging.debug.license toggle (default false)");
            return true;
        }
        return false;
    }

    private static boolean v74to75(YamlConfiguration cfg) {
        boolean changed = false;
        String[] loggingKeys = {
            "logging.debug.startup",
            "logging.debug.nms",
            "logging.debug.packets",
            "logging.debug.network",
            "logging.debug.config-sync",
            "logging.debug.database",
            "logging.debug.skin",
            "logging.debug.license",
            "logging.debug.commands",
            "logging.debug.chat",
            "logging.debug.swap",
            "logging.debug.right-click",
            "logging.debug.right-click-head",
            "logging.debug.head-ai",
            "logging.debug.general"
        };
        for (String key : loggingKeys) {
            if (cfg.isSet(key)) {
                cfg.set(key, null);
                changed = true;
            }
        }
        if (changed) {
            log("v74→v75", "removed logging.debug.* keys (moved to debug.yml)");
        }
        return changed;
    }

    private static boolean v75to76(YamlConfiguration cfg) {
        boolean changed = false;
        if (setIfMissing(cfg, "performance.enabled", true)) {
            log("v75→v76", "added performance.enabled = true");
            changed = true;
        }
        if (setIfMissing(cfg, "performance.spark-enabled", true)) {
            log("v75→v76", "added performance.spark-enabled = true");
            changed = true;
        }
        if (setIfMissing(cfg, "performance.placeholders", true)) {
            log("v75→v76", "added performance.placeholders = true");
            changed = true;
        }
        if (setIfMissing(cfg, "performance.sample-interval-ticks", 20)) {
            log("v75→v76", "added performance.sample-interval-ticks = 20");
            changed = true;
        }
        if (setIfMissing(cfg, "performance.history-minutes", 15)) {
            log("v75→v76", "added performance.history-minutes = 15");
            changed = true;
        }
        if (setIfMissing(cfg, "performance.warn-mspt", 60.0)) {
            log("v75→v76", "added performance.warn-mspt = 60.0");
            changed = true;
        }
        if (setIfMissing(cfg, "performance.warn-tps", 18.0)) {
            log("v75→v76", "added performance.warn-tps = 18.0");
            changed = true;
        }
        if (setIfMissing(cfg, "performance.warn-consecutive-samples", 3)) {
            log("v75→v76", "added performance.warn-consecutive-samples = 3");
            changed = true;
        }
        if (setIfMissing(cfg, "performance.warn-cooldown-minutes", 5)) {
            log("v75→v76", "added performance.warn-cooldown-minutes = 5");
            changed = true;
        }
        if (setIfMissing(cfg, "performance.auto-profiler-timeout-seconds", 60)) {
            log("v75→v76", "added performance.auto-profiler-timeout-seconds = 60");
            changed = true;
        }
        return changed;
    }

    private static boolean v76to77(YamlConfiguration cfg) {
        boolean changed = false;
        if (setIfMissing(cfg, "performance.self-profiler.enabled", false)) {
            log("v76→v77", "added performance.self-profiler.enabled = false");
            changed = true;
        }
        if (setIfMissing(cfg, "performance.self-profiler.method-level", false)) {
            log("v76→v77", "added performance.self-profiler.method-level = false");
            changed = true;
        }
        if (setIfMissing(cfg, "performance.self-profiler.export-on-warning", true)) {
            log("v76→v77", "added performance.self-profiler.export-on-warning = true");
            changed = true;
        }
        return changed;
    }

    private static boolean v77to78(YamlConfiguration cfg) {
        boolean changed = false;
        if (setIfMissing(cfg, "economy.enabled", false)) {
            log("v77→v78", "added economy.enabled = false");
            changed = true;
        }
        if (setIfMissing(cfg, "economy.provider", "auto")) {
            log("v77→v78", "added economy.provider = auto");
            changed = true;
        }
        if (setIfMissing(cfg, "economy.excellent-economy-currency-id", "money")) {
            log("v77→v78", "added economy.excellent-economy-currency-id = money");
            changed = true;
        }
        if (setIfMissing(cfg, "economy.rental.price-per-hour", 100.0)) {
            log("v77→v78", "added economy.rental.price-per-hour = 100.0");
            changed = true;
        }
        if (setIfMissing(cfg, "economy.rental.price-per-bot-slot", 0.0)) {
            log("v77→v78", "added economy.rental.price-per-bot-slot = 0.0");
            changed = true;
        }
        if (setIfMissing(cfg, "economy.rental.min-hours", 1)) {
            log("v77→v78", "added economy.rental.min-hours = 1");
            changed = true;
        }
        if (setIfMissing(cfg, "economy.rental.max-hours", 72)) {
            log("v77→v78", "added economy.rental.max-hours = 72");
            changed = true;
        }
        if (setIfMissing(cfg, "economy.rental.max-banked-hours", 168)) {
            log("v77→v78", "added economy.rental.max-banked-hours = 168");
            changed = true;
        }
        if (setIfMissing(cfg, "economy.rental.warn-minutes-before-expiry", 10)) {
            log("v77→v78", "added economy.rental.warn-minutes-before-expiry = 10");
            changed = true;
        }
        if (setIfMissing(cfg, "economy.rental.sweep-interval-seconds", 30)) {
            log("v77→v78", "added economy.rental.sweep-interval-seconds = 30");
            changed = true;
        }
        if (setIfMissing(cfg, "economy.rental.max-bots-per-player", 3)) {
            log("v77→v78", "added economy.rental.max-bots-per-player = 3");
            changed = true;
        }
        return changed;
    }

    private static boolean v78to79(YamlConfiguration cfg) {
        boolean changed = false;
        if (setIfMissing(cfg, "left-click.interval-ticks", 4)) {
            log("v78→v79", "added left-click.interval-ticks = 4");
            changed = true;
        }
        if (setIfMissing(cfg, "right-click.interval-ticks", 4)) {
            log("v78→v79", "added right-click.interval-ticks = 4");
            changed = true;
        }
        return changed;
    }

    private static boolean pruneUnknownKeys(ConfigurationSection target, ConfigurationSection defaults, String prefix) {
        boolean changed = false;
        Set<String> keys = new LinkedHashSet<>(target.getKeys(false));
        for (String key : keys) {
            String path = prefix.isBlank() ? key : prefix + "." + key;
            if (path.equals("config-version")) continue;
            if (!defaults.isSet(key)) {
                target.set(key, null);
                changed = true;
                continue;
            }
            ConfigurationSection targetChild = target.getConfigurationSection(key);
            ConfigurationSection defaultChild = defaults.getConfigurationSection(key);
            if (targetChild != null && defaultChild != null) {
                changed |= pruneUnknownKeys(targetChild, defaultChild, path);
            }
        }
        return changed;
    }

    private static boolean setIfMissing(YamlConfiguration cfg, String path, Object value) {
        if (cfg.isSet(path)) return false;
        cfg.set(path, value);
        return true;
    }

    private static void log(String step, String message) {
        if (rawDebug) {
            FppLogger.info("[ConfigMigrator][" + step + "] " + message);
        }
    }

    private static void log(String message) {
        if (rawDebug) {
            FppLogger.info("[ConfigMigrator] " + message);
        }
    }
}
