package me.bill.fakePlayerPlugin.util;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.fakeplayer.RemoteBotEntry;
import me.bill.fakePlayerPlugin.perf.PerfSnapshot;
import me.bill.fakePlayerPlugin.permission.Perm;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

public final class FppPlaceholderExpansion extends PlaceholderExpansion {

    private final FakePlayerPlugin plugin;
    private final FakePlayerManager manager;

    public FppPlaceholderExpansion(FakePlayerPlugin plugin, FakePlayerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "fpp";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (params == null || params.isEmpty()) {
            return "";
        }

        String p = params.toLowerCase();

        int localBots = manager.getCount();
        Collection<RemoteBotEntry> remoteEntries =
                Config.isNetworkMode() ? plugin.getRemoteBotCache().getAll() : List.of();
        int remoteBots = remoteEntries.size();
        int bots = localBots + remoteBots;
        int real = Math.max(0, Bukkit.getOnlinePlayers().size() - localBots);

        return switch (p) {
            case "count" -> String.valueOf(bots);
            case "local_count" -> String.valueOf(localBots);
            case "network_count" -> String.valueOf(remoteBots);
            case "max" -> Config.maxBots() == 0 ? "∞" : String.valueOf(Config.maxBots());
            case "real" -> String.valueOf(real);
            // Bots are never counted as players: "online"/"total" are real players only, so these
            // placeholders cannot be used to inflate a displayed player count.
            case "total", "online" -> String.valueOf(real);
            case "network_total" -> {
                var cache = plugin.getRemoteBotCache();
                if (cache != null && cache.hasNetworkStats()) {
                    yield String.valueOf(Math.max(0, cache.getNetworkTotalPlayers() - cache.getNetworkTotalBots()));
                }
                yield String.valueOf(real);
            }
            case "network_real" -> {
                var cache = plugin.getRemoteBotCache();
                if (cache != null && cache.hasNetworkStats()) {
                    yield String.valueOf(Math.max(0, cache.getNetworkTotalPlayers() - cache.getNetworkTotalBots()));
                }
                yield String.valueOf(real);
            }
            case "network_bots" -> {
                var cache = plugin.getRemoteBotCache();
                if (cache != null && cache.hasNetworkStats()) {
                    yield String.valueOf(cache.getNetworkTotalBots());
                }
                yield String.valueOf(localBots + remoteBots);
            }
            case "frozen" -> String.valueOf(manager.getActivePlayers().stream()
                    .filter(FakePlayer::isFrozen)
                    .count());
            case "names" -> joinNames(
                    manager.getActivePlayers().stream().map(FakePlayer::getDisplayName),
                    remoteEntries.stream().map(RemoteBotEntry::displayName));
            case "network_names" -> remoteEntries.stream()
                    .map(RemoteBotEntry::displayName)
                    .collect(Collectors.joining(", "));
            case "version" -> plugin.getPluginMeta().getVersion();
            case "config_version" -> String.valueOf(Config.configVersion());
            case "uptime" -> formatUptime((System.currentTimeMillis() - plugin.getEnabledAt()) / 1000);

            case "server_tps" -> getTps();
            case "server_uptime" -> formatUptime(
                    ManagementFactory.getRuntimeMXBean().getUptime() / 1000);

            case "body" -> onOff(true);
            case "pushable" -> onOff(Config.bodyPushable());
            case "damageable" -> onOff(Config.bodyDamageable());
            case "tab" -> onOff(Config.tabListEnabled());
            case "max_health" -> String.valueOf(Config.maxHealth());
            case "network" -> onOff(Config.isNetworkMode());
            case "network_mode" -> onOff(Config.isNetworkMode());
            case "server_id" -> Config.serverId();
            case "persistence" -> onOff(Config.persistOnRestart());
            case "spawn_cooldown" -> String.valueOf(Config.spawnCooldown());
            case "chunk_loading" -> onOff(Config.chunkLoadingEnabled());
            case "chunk_loading_radius" -> String.valueOf(Config.chunkLoadingRadius());
            case "head_ai" -> onOff(Config.headAiEnabled());
            case "swim_ai" -> onOff(Config.swimAiEnabled());
            case "auto_eat" -> onOff(Config.autoEatEnabled());
            case "auto_place_bed" -> onOff(Config.autoPlaceBedEnabled());
            case "auto_milk" -> onOff(Config.autoMilkEnabled());
            case "prevent_bad_omen" -> onOff(Config.preventBadOmen());
            case "fall_damage" -> onOff(Config.fallDamageEnabled());
            case "respawn_on_death" -> onOff(Config.respawnOnDeath());
            case "hurt_sound" -> onOff(Config.hurtSound());
            case "join_message" -> onOff(Config.joinMessage());
            case "leave_message" -> onOff(Config.leaveMessage());
            case "metrics" -> onOff(Config.metricsEnabled());
            case "update_checker" -> onOff(Config.updateCheckerEnabled());
            case "badword_filter" -> onOff(Config.isBadwordFilterEnabled());
            case "database" -> onOff(Config.databaseEnabled());
            case "database_mode" -> Config.databaseMode();

            case "perf_tps" -> perfValue("tps");
            case "perf_mspt" -> perfValue("mspt");
            case "perf_cpu_process" -> perfValue("cpu_process");
            case "perf_cpu_system" -> perfValue("cpu_system");
            case "perf_gc_avg_time" -> perfValue("gc_time");
            case "perf_gc_avg_frequency" -> perfValue("gc_freq");
            case "perf_health" -> perfHealth();

            case "ping_all" -> {
                if (player == null || !player.isOnline()) yield "-1";
                Player onlinePlayer = player.getPlayer();
                FakePlayer fp = manager.getByName(onlinePlayer.getName());
                yield fp != null ? String.valueOf(fp.getEffectivePing()) : String.valueOf(onlinePlayer.getPing());
            }
            case "avg_ping" -> {
                if (localBots == 0) yield "0";
                int total = 0;
                for (FakePlayer fp : manager.getActivePlayers()) total += fp.getEffectivePing();
                yield String.valueOf(total / localBots);
            }
            case "player_ping" -> {
                if (player == null || !player.isOnline()) yield "-1";
                yield String.valueOf(player.getPlayer().getPing());
            }

            case "user_count" -> player == null ? "0" : String.valueOf(countUserBots(player));
            case "user_max" -> resolveUserMax(player);
            case "user_names" -> player == null
                    ? ""
                    : getUserBots(player).stream()
                            .map(FakePlayer::getDisplayName)
                            .collect(Collectors.joining(", "));
            case "user_ping" -> {
                if (player == null) yield "0";
                List<FakePlayer> owned = getUserBots(player);
                yield owned.isEmpty() ? "0" : String.valueOf(owned.get(0).getEffectivePing());
            }
            case "user_ping_avg" -> {
                if (player == null) yield "0";
                List<FakePlayer> owned = getUserBots(player);
                if (owned.isEmpty()) yield "0";
                int sum = 0;
                for (FakePlayer fp : owned) sum += fp.getEffectivePing();
                yield String.valueOf(sum / owned.size());
            }
            case "user_frozen" -> {
                if (player == null) yield "0";
                yield String.valueOf(getUserBots(player).stream()
                        .filter(FakePlayer::isFrozen)
                        .count());
            }
            case "user_oldest" -> {
                if (player == null) yield "";
                yield getUserBots(player).stream()
                        .filter(fp -> fp.getSpawnTime() != null)
                        .min(Comparator.comparing(FakePlayer::getSpawnTime))
                        .map(FakePlayer::getDisplayName)
                        .orElse("");
            }
            case "user_newest" -> {
                if (player == null) yield "";
                yield getUserBots(player).stream()
                        .filter(fp -> fp.getSpawnTime() != null)
                        .max(Comparator.comparing(FakePlayer::getSpawnTime))
                        .map(FakePlayer::getDisplayName)
                        .orElse("");
            }
            case "user_uptime", "user_total_uptime" -> {
                if (player == null) yield "0s";
                long secs = getUserBots(player).stream()
                        .filter(fp -> fp.getSpawnTime() != null)
                        .mapToLong(fp -> Duration.between(fp.getSpawnTime(), Instant.now())
                                .getSeconds())
                        .sum();
                yield formatUptime(secs);
            }
            case "user_total_damage" -> {
                if (player == null) yield "0";
                double total = getUserBots(player).stream()
                        .mapToDouble(FakePlayer::getTotalDamageTaken)
                        .sum();
                yield String.format("%.1f", total);
            }
            case "user_deaths" -> {
                if (player == null) yield "0";
                int total = getUserBots(player).stream()
                        .mapToInt(FakePlayer::getDeathCount)
                        .sum();
                yield String.valueOf(total);
            }

            case "rental_count" -> {
                if (player == null) yield "0";
                yield String.valueOf(getUserBots(player).stream()
                        .filter(FakePlayer::isRented)
                        .count());
            }
            case "rental_remaining" -> {
                if (player == null) yield "";
                yield getUserBots(player).stream()
                        .filter(FakePlayer::isRented)
                        .map(FakePlayer::getRentalExpiresAt)
                        .min(Comparator.naturalOrder())
                        .map(expiresAt -> formatRentalRemaining(expiresAt - System.currentTimeMillis()))
                        .orElse("");
            }

            default -> handleDynamic(p, player, localBots, remoteEntries);
        };
    }

    private String handleDynamic(
            String p, OfflinePlayer player, int localBots, Collection<RemoteBotEntry> remoteEntries) {
        if (p.startsWith("count_")) {
            return String.valueOf(countBotsInWorld(p.substring(6)));
        }
        if (p.startsWith("real_")) {
            return String.valueOf(countRealInWorld(p.substring(5)));
        }
        if (p.startsWith("total_")) {
            return String.valueOf(countRealInWorld(p.substring(6)));
        }
        if (p.startsWith("user_count_")) {
            if (player == null) return "0";
            String w = p.substring(11);
            return String.valueOf(getUserBots(player).stream()
                    .filter(fp -> w.equalsIgnoreCase(getBotWorldName(fp)))
                    .count());
        }

        FakePlayer fp = null;

        if (p.startsWith("ping_")) {
            fp = manager.getByName(p.substring(5));
            return fp != null ? String.valueOf(fp.getEffectivePing()) : "N/A";
        }
        if (p.startsWith("health_")) {
            fp = manager.getByName(p.substring(7));
            if (fp == null) return "N/A";
            Player body = fp.getPlayer();
            return body != null && body.isValid()
                    ? String.valueOf((int) body.getHealth())
                    : String.valueOf(Config.maxHealth());
        }
        if (p.startsWith("health_max_")) {
            fp = manager.getByName(p.substring(11));
            return fp != null ? String.valueOf(Config.maxHealth()) : "N/A";
        }
        if (p.startsWith("world_")) {
            fp = manager.getByName(p.substring(6));
            return fp != null ? getBotWorldName(fp) : "N/A";
        }
        if (p.startsWith("loc_x_")) {
            fp = manager.getByName(p.substring(6));
            Location loc = fp != null ? fp.getLiveLocation() : null;
            return loc != null ? String.valueOf((int) loc.getX()) : "N/A";
        }
        if (p.startsWith("loc_y_")) {
            fp = manager.getByName(p.substring(6));
            Location loc = fp != null ? fp.getLiveLocation() : null;
            return loc != null ? String.valueOf((int) loc.getY()) : "N/A";
        }
        if (p.startsWith("loc_z_")) {
            fp = manager.getByName(p.substring(6));
            Location loc = fp != null ? fp.getLiveLocation() : null;
            return loc != null ? String.valueOf((int) loc.getZ()) : "N/A";
        }
        if (p.startsWith("frozen_")) {
            fp = manager.getByName(p.substring(7));
            return fp != null ? (fp.isFrozen() ? "yes" : "no") : "N/A";
        }
        if (p.startsWith("sleeping_")) {
            fp = manager.getByName(p.substring(9));
            return fp != null ? (fp.isSleeping() ? "yes" : "no") : "N/A";
        }
        if (p.startsWith("owner_") || p.startsWith("spawned_by_")) {
            String botName = p.startsWith("spawned_by_") ? p.substring(11) : p.substring(6);
            fp = manager.getByName(botName);
            return fp != null ? fp.getSpawnedBy() : "N/A";
        }
        if (p.startsWith("pve_")) {
            fp = manager.getByName(p.substring(4));
            return fp != null ? (fp.isPveEnabled() ? "yes" : "no") : "N/A";
        }
        if (p.startsWith("displayname_")) {
            fp = manager.getByName(p.substring(12));
            return fp != null ? fp.getDisplayName() : "N/A";
        }
        if (p.startsWith("uuid_")) {
            fp = manager.getByName(p.substring(5));
            return fp != null ? fp.getUuid().toString() : "N/A";
        }
        if (p.startsWith("spawn_time_")) {
            fp = manager.getByName(p.substring(11));
            if (fp == null || fp.getSpawnTime() == null) return "N/A";
            return DateTimeFormatter.ISO_INSTANT.format(fp.getSpawnTime());
        }
        if (p.startsWith("following_")) {
            fp = manager.getByName(p.substring(10));
            if (fp == null) return "N/A";
            Object target = fp.getMetadata("following_target");
            return target != null ? target.toString() : "N/A";
        }
        if (p.startsWith("task_")) {
            fp = manager.getByName(p.substring(5));
            if (fp == null) return "N/A";
            Object task = fp.getMetadata("current_task");
            return task != null ? task.toString() : "idle";
        }
        if (p.startsWith("damage_")) {
            fp = manager.getByName(p.substring(7));
            return fp != null ? String.format("%.1f", fp.getTotalDamageTaken()) : "N/A";
        }
        if (p.startsWith("deaths_")) {
            fp = manager.getByName(p.substring(7));
            return fp != null ? String.valueOf(fp.getDeathCount()) : "N/A";
        }
        if (p.startsWith("type_")) {
            fp = manager.getByName(p.substring(5));
            return fp != null ? fp.getBotType().name() : "N/A";
        }
        if (p.startsWith("skin_")) {
            fp = manager.getByName(p.substring(5));
            return fp != null ? (fp.getSkinName() != null ? fp.getSkinName() : "default") : "N/A";
        }

        return null;
    }

    private String perfValue(String metric) {
        if (!Config.performancePlaceholdersEnabled()) return "N/A";
        var monitor = plugin.getPerformanceMonitor();
        if (monitor == null) return "N/A";
        PerfSnapshot snap = monitor.latest();
        return switch (metric) {
            case "tps" -> formatDouble(snap.tps());
            case "mspt" -> formatDouble(snap.msptMean());
            case "cpu_process" -> formatDouble(snap.cpuProcess());
            case "cpu_system" -> formatDouble(snap.cpuSystem());
            case "gc_time" -> formatDouble(snap.gcAvgTime());
            case "gc_freq" -> snap.gcAvgFrequency() < 0 ? "N/A" : String.valueOf(snap.gcAvgFrequency());
            default -> "N/A";
        };
    }

    private String perfHealth() {
        if (!Config.performancePlaceholdersEnabled()) return "N/A";
        var monitor = plugin.getPerformanceMonitor();
        if (monitor == null) return "N/A";
        PerfSnapshot snap = monitor.latest();
        return snap.healthScore() + "/" + snap.healthLabel();
    }

    private String formatDouble(double value) {
        if (value < 0 || Double.isNaN(value) || Double.isInfinite(value)) return "N/A";
        return String.format(java.util.Locale.US, "%.2f", value);
    }

    private int countBotsInWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) return 0;
        return (int) manager.getActivePlayers().stream()
                .filter(fp -> worldName.equalsIgnoreCase(getBotWorldName(fp)))
                .count();
    }

    private int countRealInWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) return 0;
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            world = Bukkit.getWorlds().stream()
                    .filter(w -> w.getName().equalsIgnoreCase(worldName))
                    .findFirst()
                    .orElse(null);
        }
        if (world == null) return 0;
        int botsInWorld = countBotsInWorld(worldName);
        return Math.max(0, world.getPlayers().size() - botsInWorld);
    }

    private List<FakePlayer> getUserBots(OfflinePlayer player) {
        if (player == null) return List.of();
        return manager.getBotsOwnedBy(player.getUniqueId());
    }

    private int countUserBots(OfflinePlayer player) {
        if (player == null) return 0;
        return manager.getBotsOwnedBy(player.getUniqueId()).size();
    }

    private String resolveUserMax(OfflinePlayer player) {
        if (player == null) return String.valueOf(Config.userBotLimit());
        Player online = player.getPlayer();
        if (online == null) return String.valueOf(Config.userBotLimit());
        int personal = Perm.resolveUserBotLimit(online);
        return personal < 0 ? String.valueOf(Config.userBotLimit()) : String.valueOf(personal);
    }

    private static String getBotWorldName(FakePlayer fp) {
        if (fp == null) return "";
        Entity body = fp.getPhysicsEntity();
        if (body != null && body.isValid()) {
            World w = body.getLocation().getWorld();
            if (w != null) return w.getName();
        }
        Location sl = fp.getSpawnLocation();
        if (sl != null && sl.getWorld() != null) return sl.getWorld().getName();
        return "";
    }

    private static String joinNames(Stream<String> local, Stream<String> remote) {
        if (Config.isNetworkMode()) {
            return Stream.concat(local, remote).collect(Collectors.joining(", "));
        }
        return local.collect(Collectors.joining(", "));
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    private static String getTps() {
        try {
            double[] tps = Bukkit.getServer().getTPS();
            double t = tps.length > 0 ? tps[0] : 20.0;
            return String.format("%.1f", t);
        } catch (Exception e) {
            return "N/A";
        }
    }

    private static String formatUptime(long secs) {
        if (secs < 60) return secs + "s";
        if (secs < 3600) return (secs / 60) + "m " + (secs % 60) + "s";
        long h = secs / 3600, m = (secs % 3600) / 60;
        return h + "h " + m + "m";
    }

    private static String formatRentalRemaining(long millis) {
        if (millis <= 0) return "0m";
        long totalMinutes = millis / 60_000L;
        long h = totalMinutes / 60, m = totalMinutes % 60;
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }
}
