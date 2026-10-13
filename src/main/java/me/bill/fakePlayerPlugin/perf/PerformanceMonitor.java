package me.bill.fakePlayerPlugin.perf;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.util.FppLogger;
import me.bill.fakePlayerPlugin.util.FppScheduler;

/**
 * Lightweight continuous performance monitor for FPP. Collects snapshots via
 * a configurable provider, maintains rolling history, computes a health score,
 * and emits threshold warnings with consecutive-sample logic and cooldown.
 */
public final class PerformanceMonitor {

    private final FakePlayerPlugin plugin;
    private final FakePlayerManager manager;
    private final PerfDataProvider provider;
    private final BuiltinFppProfiler profiler;
    private PerformanceReportExporter reportExporter;
    private final LinkedList<PerfSnapshot> history = new LinkedList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger consecutiveWarnings = new AtomicInteger(0);
    private volatile Instant lastWarningAt = Instant.MIN;
    private volatile int taskId = -1;

    public PerformanceMonitor(FakePlayerPlugin plugin, FakePlayerManager manager) {
        this.plugin = Objects.requireNonNull(plugin);
        this.manager = Objects.requireNonNull(manager);
        this.profiler = plugin.getBuiltinProfiler();
        PerfDataProvider spark = new SparkPerfProvider();
        this.provider = spark.available() ? spark : new BuiltinPerfProvider();
    }

    public void setReportExporter(PerformanceReportExporter reportExporter) {
        this.reportExporter = reportExporter;
    }

    public void start() {
        if (!Config.performanceEnabled()) {
            FppLogger.debug("PerformanceMonitor disabled in config.");
            return;
        }
        if (!running.compareAndSet(false, true)) return;
        long period = Config.performanceSampleIntervalTicks();
        taskId = FppScheduler.runSyncRepeatingWithId(plugin, this::tick, period, period);
        FppLogger.debug("PerformanceMonitor started (provider=" + provider.name() + ", period=" + period + "t).");
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (taskId >= 0) {
            FppScheduler.cancelTask(taskId);
            taskId = -1;
        }
        history.clear();
    }

    private void tick() {
        if (!running.get()) return;
        try {
            PerfSnapshot snapshot = provider.snapshot(manager.getCount());
            synchronized (history) {
                history.addLast(snapshot);
                pruneHistory();
            }
            evaluateWarnings(snapshot);
        } catch (Exception e) {
            FppLogger.debug("PerformanceMonitor tick failed: " + e.getMessage());
        }
    }

    private void pruneHistory() {
        int maxMinutes = Config.performanceHistoryMinutes();
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(maxMinutes));
        while (!history.isEmpty() && history.getFirst().timestamp().isBefore(cutoff)) {
            history.removeFirst();
        }
    }

    private void evaluateWarnings(PerfSnapshot snapshot) {
        double warnMspt = Config.performanceWarnMspt();
        double warnTps = Config.performanceWarnTps();
        boolean bad = snapshot.msptMean() >= warnMspt || snapshot.tps() <= warnTps;
        if (!bad) {
            consecutiveWarnings.set(0);
            return;
        }

        if (profiler != null) profiler.onHeavyLoad((long) snapshot.msptMean());

        int consecutive = consecutiveWarnings.incrementAndGet();
        int required = Config.performanceWarnConsecutiveSamples();
        if (consecutive < required) return;

        Instant now = Instant.now();
        int cooldownMinutes = Config.performanceWarnCooldownMinutes();
        if (cooldownMinutes > 0) {
            long elapsed = Duration.between(lastWarningAt, now).toMinutes();
            if (elapsed < cooldownMinutes) return;
        }
        lastWarningAt = now;

        String reason;
        if (snapshot.msptMean() >= warnMspt && snapshot.tps() <= warnTps) {
            reason = "MSPT " + String.format("%.1f", snapshot.msptMean()) + "ms and TPS "
                    + String.format("%.1f", snapshot.tps());
        } else if (snapshot.msptMean() >= warnMspt) {
            reason = "MSPT " + String.format("%.1f", snapshot.msptMean()) + "ms";
        } else {
            reason = "TPS " + String.format("%.1f", snapshot.tps());
        }

        FppLogger.warn("═══════════════════════════════════════════════════════════════════");
        FppLogger.warn("  ⚠  FPP Performance Warning (" + reason + ")  ⚠");
        FppLogger.warn("  Health Score: " + snapshot.healthScore() + "/100 (" + snapshot.healthLabel() + ")");
        FppLogger.warn("  Bots: " + snapshot.botCount() + " | Players: " + snapshot.onlinePlayers() + " | Entities: "
                + snapshot.entityCount());
        FppLogger.warn("  Run /fpp perf spark to generate a Spark profile.");
        FppLogger.warn("  Run /fpp perf report to export the built-in FPP self-profiler report.");
        FppLogger.warn("═══════════════════════════════════════════════════════════════════");

        if (reportExporter != null) {
            try {
                File report = reportExporter.export(Config.performanceHistoryMinutes(), false);
                FppLogger.warn("  Self-profiler report exported to: " + report.getPath());
            } catch (Exception e) {
                FppLogger.debug("Failed to auto-export performance report: " + e.getMessage());
            }
        }

        consecutiveWarnings.set(0);
    }

    public PerfSnapshot latest() {
        synchronized (history) {
            return history.isEmpty() ? provider.snapshot(manager.getCount()) : history.getLast();
        }
    }

    public List<PerfSnapshot> history() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    public HistorySummary summary(int minutes) {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(Math.max(1, minutes)));
        List<PerfSnapshot> window;
        synchronized (history) {
            window = history.stream()
                    .filter(s -> !s.timestamp().isBefore(cutoff))
                    .toList();
        }
        if (window.isEmpty()) {
            PerfSnapshot latest = latest();
            return new HistorySummary(latest, latest, latest, latest, latest, 1);
        }

        PerfSnapshot minTps = window.get(0);
        PerfSnapshot maxTps = window.get(0);
        PerfSnapshot minMspt = window.get(0);
        PerfSnapshot maxMspt = window.get(0);
        double sumTps = 0;
        double sumMspt = 0;
        double sumCpu = 0;
        int cpuCount = 0;
        for (PerfSnapshot s : window) {
            if (s.tps() < minTps.tps()) minTps = s;
            if (s.tps() > maxTps.tps()) maxTps = s;
            if (s.msptMean() < minMspt.msptMean()) minMspt = s;
            if (s.msptMean() > maxMspt.msptMean()) maxMspt = s;
            sumTps += s.tps();
            sumMspt += s.msptMean();
            if (s.cpuProcess() >= 0) {
                sumCpu += s.cpuProcess();
                cpuCount++;
            }
        }

        PerfSnapshot avg = new PerfSnapshot(
                window.get(window.size() - 1).timestamp(),
                sumTps / window.size(),
                sumMspt / window.size(),
                -1,
                cpuCount == 0 ? -1 : sumCpu / cpuCount,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1);
        return new HistorySummary(minTps, maxTps, minMspt, maxMspt, avg, window.size());
    }

    public PerfDataProvider provider() {
        return provider;
    }

    public void runSparkProfiler(CommandSenderCallback callback) {
        if (!Config.performanceSparkEnabled()) {
            callback.send("Spark profiler integration is disabled in config.yml.");
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("spark") == null) {
            callback.send("Spark is not installed. Download it from https://spark.lucko.me/");
            callback.send("Run profiler manually with: /spark profiler --thread \"Server Thread\" --timeout 60");
            return;
        }
        int timeout = Config.performanceAutoProfilerTimeoutSeconds();
        String command = "spark profiler --thread \"Server Thread\" --timeout " + timeout;
        try {
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            if (ok) {
                callback.send("Started Spark profiler for " + timeout + " seconds. A link will appear in console.");
                callback.send("Tip: you can also run /spark profiler open to view the background profile.");
            } else {
                callback.send("Spark profiler command was rejected. Try running it manually:");
                callback.send("/spark profiler --thread \"Server Thread\" --timeout " + timeout);
            }
        } catch (Exception e) {
            callback.send("Failed to dispatch Spark profiler: " + e.getMessage());
            callback.send("Run manually: /spark profiler --thread \"Server Thread\" --timeout " + timeout);
        }
    }

    public interface CommandSenderCallback {
        void send(String message);
    }

    public record HistorySummary(
            PerfSnapshot minTps,
            PerfSnapshot maxTps,
            PerfSnapshot minMspt,
            PerfSnapshot maxMspt,
            PerfSnapshot avg,
            int samples) {

        public HistorySummary {
            Objects.requireNonNull(minTps);
            Objects.requireNonNull(maxTps);
            Objects.requireNonNull(minMspt);
            Objects.requireNonNull(maxMspt);
            Objects.requireNonNull(avg);
        }
    }
}
