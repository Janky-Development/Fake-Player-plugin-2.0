package me.bill.fakePlayerPlugin.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import me.bill.fakePlayerPlugin.config.Config;

public final class ConfigValidator {

    private ConfigValidator() {}

    public static int validate() {
        int issues = 0;

        if (Config.chunkLoadingEnabled()) {
            int viewDistance = Config.chunkLoadingRadius();
            if (viewDistance == 0) {
                FppLogger.info("[Config] chunk-loading.view-distance is 0 - bots report the minimum render distance.");
            } else if (viewDistance > 32) {
                FppLogger.warn("[Config] chunk-loading.view-distance exceeds 32 (the client maximum) - clamping.");
                issues++;
            }
        }

        if (Config.maxBots() < 0) {
            FppLogger.warn("[Config] limits.max-bots is negative - treating as 0 (unlimited).");
            issues++;
        }
        if (Config.userBotLimit() < 1) {
            FppLogger.warn("[Config] limits.user-bot-limit is < 1 - users will never be able to spawn.");
            issues++;
        }

        if (Config.spawnCooldown() < 0) {
            FppLogger.warn("[Config] spawn-cooldown is negative - treating as 0 (no cooldown).");
            issues++;
        }

        if (Config.maxHealth() <= 0) {
            FppLogger.warn("[Config] combat.max-health must be > 0. Defaulting to 20.");
            issues++;
        }

        if (Config.headAiLookRange() <= 0) {
            FppLogger.warn("[Config] head-ai.look-range must be > 0.");
            issues++;
        }
        float turnSpeed = Config.headAiTurnSpeed();
        if (turnSpeed <= 0 || turnSpeed > 1) {
            FppLogger.warn("[Config] head-ai.turn-speed should be between 0.0 and 1.0 (got " + turnSpeed + ").");
            issues++;
        }

        if (Config.collisionMaxHoriz() <= 0) {
            FppLogger.warn("[Config] collision.max-horizontal-speed must be > 0.");
            issues++;
        }
        if (Config.collisionHitStrength() < 0) {
            FppLogger.warn("[Config] collision.hit-strength must be >= 0.");
            issues++;
        }
        if (Config.collisionHitMaxHoriz() <= 0) {
            FppLogger.warn("[Config] collision.hit-max-horizontal-speed must be > 0.");
            issues++;
        }
        if (Config.collisionWalkRadius() <= 0) {
            FppLogger.warn("[Config] collision.walk-radius must be > 0.");
            issues++;
        }
        if (Config.collisionBotRadius() <= 0) {
            FppLogger.warn("[Config] collision.bot-radius must be > 0.");
            issues++;
        }
        if (Config.collisionWalkStrength() < 0) {
            FppLogger.warn("[Config] collision.walk-strength must be >= 0.");
            issues++;
        }
        if (Config.collisionBotStrength() < 0) {
            FppLogger.warn("[Config] collision.bot-strength must be >= 0.");
            issues++;
        }

        Plugin fpp = Bukkit.getPluginManager().getPlugin("FakePlayerPlugin");
        if (fpp != null) {
            String rawMode = fpp.getConfig().getString("database.mode", "LOCAL");
            if (!rawMode.trim().equalsIgnoreCase("LOCAL") && !rawMode.trim().equalsIgnoreCase("NETWORK")) {
                FppLogger.warn("[Config] database.mode \""
                        + rawMode.trim()
                        + "\" is not valid "
                        + "(accepted: LOCAL, NETWORK) - falling back to LOCAL.");
                issues++;
            }
        }

        if (issues == 0) {
            FppLogger.debug("[Config] All values passed validation.");
        } else {
            FppLogger.warn("[Config] " + issues + " config issue(s) detected - review warnings above.");
        }

        return issues;
    }
}
