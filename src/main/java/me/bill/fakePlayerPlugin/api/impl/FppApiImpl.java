package me.bill.fakePlayerPlugin.api.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.FppApi;
import me.bill.fakePlayerPlugin.api.FppBot;
import me.bill.fakePlayerPlugin.api.FppBotTickHandler;
import me.bill.fakePlayerPlugin.api.FppClickMode;
import me.bill.fakePlayerPlugin.api.FppCommandInfo;
import me.bill.fakePlayerPlugin.api.FppCommandSource;
import me.bill.fakePlayerPlugin.api.FppNavigationGoal;
import me.bill.fakePlayerPlugin.api.event.FppBotTaskEvent;
import me.bill.fakePlayerPlugin.command.FppCommand;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.fakeplayer.PathfindingService;
import me.bill.fakePlayerPlugin.fakeplayer.PathfindingService.NavigationRequest;
import me.bill.fakePlayerPlugin.fakeplayer.PathfindingService.Owner;
import me.bill.fakePlayerPlugin.util.BotAccess;
import me.bill.fakePlayerPlugin.util.FppLogger;

/**
 * Internal implementation of {@link FppApi}.
 * Obtained via {@link FakePlayerPlugin#getFppApi()}.
 */
public final class FppApiImpl implements FppApi {

    private final FakePlayerPlugin plugin;
    private final FakePlayerManager manager;

    /**
     * Registered bot tick handlers - thread-safe iterate, rare write.
     */
    private final CopyOnWriteArrayList<FppBotTickHandler> tickHandlers = new CopyOnWriteArrayList<>();

    public FppApiImpl(@NotNull FakePlayerPlugin plugin, @NotNull FakePlayerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ── Bot queries ───────────────────────────────────────────────────────────

    @Override
    public @NotNull Collection<FppBot> getBots() {
        Collection<FakePlayer> raw = manager.getActivePlayers();
        List<FppBot> result = new ArrayList<>(raw.size());
        for (FakePlayer fp : raw) result.add(new FppBotImpl(fp));
        return result;
    }

    @Override
    public @NotNull Collection<FppBot> getBotsControllableBy(@NotNull Player player) {
        Collection<FakePlayer> raw = manager.getActivePlayers();
        List<FppBot> result = new ArrayList<>(raw.size());
        for (FakePlayer fp : raw) {
            if (BotAccess.canAdminister(player, fp)) result.add(new FppBotImpl(fp));
        }
        return result;
    }

    @Override
    public @NotNull Optional<FppBot> getBot(@NotNull String name) {
        FakePlayer fp = manager.getByName(name);
        return fp == null ? Optional.empty() : Optional.of(new FppBotImpl(fp));
    }

    @Override
    public @NotNull Optional<FppBot> getBot(@NotNull UUID uuid) {
        FakePlayer fp = manager.getByUuid(uuid);
        return fp == null ? Optional.empty() : Optional.of(new FppBotImpl(fp));
    }

    @Override
    public boolean isBot(@NotNull Player player) {
        if (FakePlayerManager.FAKE_PLAYER_KEY == null) return false;
        String marker =
                player.getPersistentDataContainer().get(FakePlayerManager.FAKE_PLAYER_KEY, PersistentDataType.STRING);
        FakePlayer fp = manager.getByUuid(player.getUniqueId());
        if (fp == null) return false;
        if (marker != null && marker.startsWith("fpp-visual:")) return true;
        return fp.getName().equalsIgnoreCase(player.getName());
    }

    @Override
    public @NotNull Optional<FppBot> asBot(@NotNull Player player) {
        if (!isBot(player)) return Optional.empty();
        return getBot(player.getUniqueId());
    }

    @Override
    public boolean canControlBot(@NotNull Player player, @NotNull FppBot bot) {
        FakePlayer fp = manager.getByUuid(bot.getUuid());
        return fp != null && BotAccess.canAdminister(player, fp);
    }

    @Override
    public int getBotCount() {
        return manager.getActivePlayers().size();
    }

    // ── Spawn / despawn ───────────────────────────────────────────────────────

    @Override
    public @NotNull Optional<FppBot> spawnBot(
            @NotNull Location location, @Nullable Player spawner, @Nullable String name) {

        int result = manager.spawn(location, 1, spawner, name, /* bypassMax */ false);
        if (result <= 0) return Optional.empty();

        // If a custom name was given we can look it up immediately.
        if (name != null) {
            FakePlayer fp = manager.getByName(name);
            return fp == null ? Optional.empty() : Optional.of(new FppBotImpl(fp));
        }

        // Random name: find the most-recently added bot at this location owned by this spawner.
        String spawnerName = spawner != null ? spawner.getName() : "CONSOLE";
        long now = System.currentTimeMillis();
        FakePlayer newest = null;
        for (FakePlayer fp : manager.getActivePlayers()) {
            if (fp.getSpawnedBy().equals(spawnerName)
                    && (now - fp.getSpawnTime().toEpochMilli()) < 2000) {
                if (newest == null || fp.getSpawnTime().isAfter(newest.getSpawnTime())) {
                    newest = fp;
                }
            }
        }
        return newest == null ? Optional.empty() : Optional.of(new FppBotImpl(newest));
    }

    @Override
    public boolean despawnBot(@NotNull String name) {
        return manager.delete(name, "api_despawn");
    }

    @Override
    public boolean despawnBot(@NotNull FppBot bot) {
        return manager.delete(bot.getName(), "api_despawn");
    }

    @Override
    public boolean despawnBotForLoginHandoff(@NotNull String name) {
        return manager.deleteForLoginHandoff(name, null);
    }

    // ── Command info ──────────────────────────────────────────────────────────

    @Override
    public @NotNull List<FppCommandInfo> getRegisteredCommands() {
        return collectRegisteredCommands(null);
    }

    @Override
    public @NotNull List<FppCommandInfo> getRegisteredCommands(@NotNull CommandSender sender) {
        return collectRegisteredCommands(sender);
    }

    private @NotNull List<FppCommandInfo> collectRegisteredCommands(@Nullable CommandSender sender) {
        var cmdManager = plugin.getCommandManager();
        List<FppCommandInfo> result = new ArrayList<>();

        if (cmdManager != null) {
            for (FppCommand command : cmdManager.getCommands()) {
                if (sender != null && !command.canUse(sender)) continue;
                result.add(new FppCommandInfo(
                        command.getName(),
                        List.copyOf(command.getAliases()),
                        safe(command.getUsage()),
                        safe(command.getDescription()),
                        command.getPermission(),
                        FppCommandSource.CORE,
                        false));
            }
        }
        return List.copyOf(result);
    }

    private static String safe(@Nullable String value) {
        return value != null ? value : "";
    }

    // ── Tick hooks ────────────────────────────────────────────────────────────

    @Override
    public void registerTickHandler(@NotNull FppBotTickHandler handler) {
        tickHandlers.addIfAbsent(handler);
    }

    @Override
    public void unregisterTickHandler(@NotNull FppBotTickHandler handler) {
        tickHandlers.remove(handler);
    }

    /**
     * Called by {@link FakePlayerManager}'s tick loop for each active, non-frozen, bodied bot.
     * Runs on the main thread.
     */
    public void fireTickHandlers(@NotNull FakePlayer fp, @NotNull Player entity) {
        if (tickHandlers.isEmpty()) return;
        FppBotImpl view = new FppBotImpl(fp);
        for (FppBotTickHandler h : tickHandlers) {
            try {
                h.onTick(view, entity);
            } catch (Throwable t) {
                FppLogger.warn(
                        "[FppApi] Tick handler threw an exception for bot '" + fp.getName() + "': " + t.getMessage());
            }
        }
    }

    /**
     * Fire a task lifecycle event for a bot. Convenience for commands.
     */
    public static void fireTaskEvent(
            @NotNull FakePlayer fp, @NotNull String taskType, @NotNull FppBotTaskEvent.Action action) {
        Bukkit.getPluginManager().callEvent(new FppBotTaskEvent(new FppBotImpl(fp), taskType, action));
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    @Override
    public void navigateTo(@NotNull FppBot bot, @NotNull Location destination, @Nullable Runnable onArrive) {

        FakePlayer fp = manager.getByUuid(bot.getUuid());
        PathfindingService svc = plugin.getPathfindingService();
        if (fp == null || svc == null) return;

        final Location dest = destination.clone();
        svc.navigate(
                fp,
                new NavigationRequest(
                        Owner.SYSTEM,
                        () -> dest,
                        /* arrivalDistance      */ 1.5,
                        /* recalcDistance       */ 3.5,
                        /* maxNullRecalcs       */ 5,
                        /* onArrive             */ () -> {
                            if (onArrive != null) onArrive.run();
                        },
                        /* onCancel             */ () -> {},
                        /* onPathFailure        */ () -> {}));
    }

    @Override
    public void navigateTo(
            @NotNull FppBot bot,
            @NotNull Location destination,
            @Nullable Runnable onArrive,
            @Nullable Runnable onFail,
            @Nullable Runnable onCancel) {
        navigateTo(bot, destination, onArrive, onFail, onCancel, 1.5);
    }

    @Override
    public void navigateTo(
            @NotNull FppBot bot,
            @NotNull Location destination,
            @Nullable Runnable onArrive,
            @Nullable Runnable onFail,
            @Nullable Runnable onCancel,
            double arrivalDistance) {

        FakePlayer fp = manager.getByUuid(bot.getUuid());
        PathfindingService svc = plugin.getPathfindingService();
        if (fp == null || svc == null) return;

        final Location dest = destination.clone();
        svc.navigate(
                fp,
                new NavigationRequest(
                        Owner.SYSTEM,
                        () -> dest,
                        arrivalDistance,
                        /* recalcDistance */ 3.5,
                        /* maxNullRecalcs */ 5,
                        /* onArrive */ () -> {
                            if (onArrive != null) onArrive.run();
                        },
                        /* onCancel */ () -> {
                            if (onCancel != null) onCancel.run();
                        },
                        /* onPathFailure */ () -> {
                            if (onFail != null) onFail.run();
                        }));
    }

    @Override
    public void cancelNavigation(@NotNull FppBot bot) {
        PathfindingService svc = plugin.getPathfindingService();
        if (svc != null) svc.cancel(bot.getUuid());
    }

    @Override
    public void setNavigationGoal(@NotNull FppBot bot, @NotNull FppNavigationGoal goal) {
        FakePlayer fp = manager.getByUuid(bot.getUuid());
        PathfindingService svc = plugin.getPathfindingService();
        if (fp == null || svc == null) return;
        final FppNavigationGoal g = goal;
        svc.navigate(
                fp,
                new NavigationRequest(
                        Owner.SYSTEM,
                        () -> g.getNextWaypoint(new FppBotImpl(fp)),
                        g.getArrivalDistance(),
                        g.getRecalcDistance(),
                        /* maxNullRecalcs */ 5,
                        /* onArrive */ () -> {
                            if (g.isComplete(new FppBotImpl(fp))) {
                                cancelNavigation(bot);
                            }
                        },
                        /* onCancel */ () -> {},
                        /* onPathFailure */ () -> {}));
    }

    @Override
    public void clearNavigationGoal(@NotNull FppBot bot) {
        cancelNavigation(bot);
    }

    @Override
    public boolean isNavigating(@NotNull FppBot bot) {
        PathfindingService svc = plugin.getPathfindingService();
        return svc != null && svc.isNavigating(bot.getUuid());
    }

    @Override
    public boolean leftClick(@NotNull FppBot bot) {
        return leftClick(bot, FppClickMode.ONCE);
    }

    @Override
    public boolean leftClick(@NotNull FppBot bot, @NotNull FppClickMode mode) {
        FakePlayer fp = manager.getByUuid(bot.getUuid());
        return fp != null
                && plugin.getLeftClickCommand() != null
                && plugin.getLeftClickCommand().click(fp, mode);
    }

    @Override
    public boolean rightClick(@NotNull FppBot bot) {
        return rightClick(bot, FppClickMode.ONCE);
    }

    @Override
    public boolean rightClick(@NotNull FppBot bot, @NotNull FppClickMode mode) {
        FakePlayer fp = manager.getByUuid(bot.getUuid());
        return fp != null
                && plugin.getRightClickCommand() != null
                && plugin.getRightClickCommand().click(fp, mode);
    }

    @Override
    public void setBotPing(@NotNull FppBot bot, int pingMs) {
        // Fabricated latency is no longer supported; bots always report no ping.
        FakePlayer fp = manager.getByUuid(bot.getUuid());
        if (fp == null) return;
        manager.applyPing(fp, -1);
    }

    @Override
    public void resetBotPing(@NotNull FppBot bot) {
        FakePlayer fp = manager.getByUuid(bot.getUuid());
        if (fp == null) return;
        manager.applyPing(fp, -1);
        manager.persistBotSettings(fp);
    }

    @Override
    public void persistBotSettings(@NotNull FppBot bot) {
        FakePlayer fp = manager.getByUuid(bot.getUuid());
        if (fp != null) manager.persistBotSettings(fp);
    }

    // ── Plugin info ───────────────────────────────────────────────────────────

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public @Nullable Player getOnlinePlayer(@NotNull String name) {
        return Bukkit.getPlayer(name);
    }

    @Override
    public int getOnlineCount() {
        return Bukkit.getOnlinePlayers().size();
    }

    @Override
    public @NotNull Collection<FppBot> getBotsOwnedBy(@NotNull Player player) {
        Collection<FakePlayer> raw = manager.getActivePlayers();
        List<FppBot> result = new ArrayList<>(raw.size());
        UUID uuid = player.getUniqueId();
        for (FakePlayer fp : raw) {
            if (uuid.equals(fp.getSpawnedByUuid())) result.add(new FppBotImpl(fp));
        }
        return result;
    }

    @Override
    public boolean runAsBot(@NotNull FppBot bot, @NotNull String command) {
        FakePlayer fp = manager.getByUuid(bot.getUuid());
        if (fp == null) return false;
        Player entity = fp.getPlayer();
        if (entity != null && entity.isOnline() && !fp.isBodyless()) {
            return Bukkit.dispatchCommand(entity, command);
        }
        return false;
    }

    @Override
    public boolean isBotOnline(@NotNull UUID uuid) {
        FakePlayer fp = manager.getByUuid(uuid);
        return fp != null && fp.getPlayer() != null && fp.getPlayer().isOnline();
    }

    @Override
    public @NotNull FakePlayerPlugin getPlugin() {
        return plugin;
    }
}
