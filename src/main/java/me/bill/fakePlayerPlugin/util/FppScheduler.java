package me.bill.fakePlayerPlugin.util;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

public final class FppScheduler {

    private static final AtomicInteger NEXT_TASK_ID = new AtomicInteger(1);
    private static final Map<Integer, ScheduledTask> TASKS = new ConcurrentHashMap<>();

    private FppScheduler() {}

    /**
     * Once a plugin is disabled, Folia's region schedulers reject any new task registration
     * ({@code IllegalPluginAccessException}) - but by the time {@code onDisable()} runs, Folia has
     * already halted all region ticking, so it's both safe and necessary to just run the work
     * directly instead of scheduling it.
     */
    private static boolean cannotSchedule(Plugin plugin) {
        return plugin != null && !plugin.isEnabled();
    }

    public static void runAtEntity(Plugin plugin, Entity entity, Runnable runnable) {
        if (entity == null) return;
        if (cannotSchedule(plugin)) {
            runnable.run();
            return;
        }
        String section = plugin instanceof FakePlayerPlugin ? "scheduler.runAtEntity" : null;
        entity.getScheduler().run(plugin, ignored -> profileRun(section, runnable), null);
    }

    public static int runAtEntityRepeatingWithId(
            Plugin plugin, Entity entity, Runnable runnable, long delayTicks, long periodTicks) {
        if (entity == null || cannotSchedule(plugin)) return -1;
        String section = plugin instanceof FakePlayerPlugin ? "scheduler.runAtEntityRepeating" : null;
        ScheduledTask task = entity.getScheduler()
                .runAtFixedRate(
                        plugin,
                        ignored -> profileRun(section, runnable),
                        null,
                        normalizeDelay(delayTicks),
                        normalizePeriod(periodTicks));
        return register(task);
    }

    public static int runAtEntityLaterWithId(Plugin plugin, Entity entity, Runnable runnable, long delayTicks) {
        if (entity == null) return -1;
        if (cannotSchedule(plugin)) {
            runnable.run();
            return -1;
        }
        String section = plugin instanceof FakePlayerPlugin ? "scheduler.runAtEntityLater" : null;
        ScheduledTask task = entity.getScheduler()
                .runDelayed(plugin, ignored -> profileRun(section, runnable), null, normalizeDelay(delayTicks));
        return register(task);
    }

    public static void runAtLocation(Plugin plugin, Location location, Runnable runnable) {
        if (location == null || location.getWorld() == null) {
            runSync(plugin, runnable);
            return;
        }
        if (cannotSchedule(plugin)) {
            runnable.run();
            return;
        }
        String section = plugin instanceof FakePlayerPlugin ? "scheduler.runAtLocation" : null;
        Bukkit.getRegionScheduler().run(plugin, location, ignored -> profileRun(section, runnable));
    }

    public static void runAtChunk(Plugin plugin, World world, int chunkX, int chunkZ, Runnable runnable) {
        if (world == null) {
            runSync(plugin, runnable);
            return;
        }
        if (cannotSchedule(plugin)) {
            runnable.run();
            return;
        }
        String section = plugin instanceof FakePlayerPlugin ? "scheduler.runAtChunk" : null;
        Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ, ignored -> profileRun(section, runnable));
    }

    public static void runSync(Plugin plugin, Runnable runnable) {
        if (cannotSchedule(plugin)) {
            runnable.run();
            return;
        }
        String section = plugin instanceof FakePlayerPlugin ? "scheduler.runSync" : null;
        Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> profileRun(section, runnable));
    }

    public static void runSyncRepeating(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        if (cannotSchedule(plugin)) return;
        String section = plugin instanceof FakePlayerPlugin ? "scheduler.runSyncRepeating" : null;
        Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(
                        plugin,
                        ignored -> profileRun(section, runnable),
                        normalizeDelay(delayTicks),
                        normalizePeriod(periodTicks));
    }

    public static int runSyncLaterWithId(Plugin plugin, Runnable runnable, long delayTicks) {
        if (cannotSchedule(plugin)) {
            runnable.run();
            return -1;
        }
        String section = plugin instanceof FakePlayerPlugin ? "scheduler.runSyncLater" : null;
        ScheduledTask task = Bukkit.getGlobalRegionScheduler()
                .runDelayed(plugin, ignored -> profileRun(section, runnable), normalizeDelay(delayTicks));
        return register(task);
    }

    public static int runSyncRepeatingWithId(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        return runSyncRepeatingWithId(plugin, null, runnable, delayTicks, periodTicks);
    }

    public static int runSyncRepeatingWithId(
            Plugin plugin, org.bukkit.entity.Entity entity, Runnable runnable, long delayTicks, long periodTicks) {
        if (cannotSchedule(plugin)) return -1;
        if (isFolia() && entity != null) {
            String section = plugin instanceof FakePlayerPlugin ? "scheduler.runSyncRepeatingAtEntity" : null;
            ScheduledTask task = entity.getScheduler()
                    .runAtFixedRate(
                            plugin,
                            ignored -> profileRun(section, runnable),
                            null,
                            normalizeDelay(delayTicks),
                            normalizePeriod(periodTicks));
            return register(task);
        }
        String section = plugin instanceof FakePlayerPlugin ? "scheduler.runSyncRepeating" : null;
        ScheduledTask task = Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(
                        plugin,
                        ignored -> profileRun(section, runnable),
                        normalizeDelay(delayTicks),
                        normalizePeriod(periodTicks));
        return register(task);
    }

    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.ThreadedRegionizer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static void runSyncLater(Plugin plugin, Runnable runnable, long delayTicks) {
        if (cannotSchedule(plugin)) {
            runnable.run();
            return;
        }
        String section = plugin instanceof FakePlayerPlugin ? "scheduler.runSyncLater" : null;
        Bukkit.getGlobalRegionScheduler()
                .runDelayed(plugin, ignored -> profileRun(section, runnable), normalizeDelay(delayTicks));
    }

    public static void runAsync(Plugin plugin, Runnable runnable) {
        if (cannotSchedule(plugin)) {
            runnable.run();
            return;
        }
        String section = plugin instanceof FakePlayerPlugin ? "scheduler.runAsync" : null;
        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> profileRun(section, runnable));
    }

    private static void profileRun(String section, Runnable runnable) {
        // Scheduler wrapper profiling is intentionally disabled: it adds overhead to every
        // scheduler callback and distorts real FPP logic measurements.
        runnable.run();
    }

    public static CompletableFuture<Boolean> teleportAsync(Entity entity, Location dest) {
        if (entity == null || dest == null) return CompletableFuture.completedFuture(false);
        return entity.teleportAsync(dest, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    public static void cancel(Plugin plugin, int taskId) {
        cancelTask(taskId);
    }

    public static void cancelTask(int taskId) {
        ScheduledTask task = TASKS.remove(taskId);
        if (task != null) {
            task.cancel();
        }
    }

    private static int register(ScheduledTask task) {
        if (task == null) return -1;
        int id = NEXT_TASK_ID.getAndIncrement();
        TASKS.put(id, task);
        return id;
    }

    private static long normalizeDelay(long delayTicks) {
        return Math.max(1L, delayTicks);
    }

    private static long normalizePeriod(long periodTicks) {
        return Math.max(1L, periodTicks);
    }
}
