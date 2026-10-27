package me.bill.fakePlayerPlugin.perf;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.World;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.util.FppLogger;

public final class PerformanceReportExporter {

    private static final int NAME_WIDTH = 60;
    private static final int PERCENT_WIDTH = 7;
    private static final int MS_WIDTH = 8;
    private static final int SAMPLES_WIDTH = 10;

    private final FakePlayerPlugin plugin;
    private final PerformanceMonitor monitor;
    private final FppProfiler profiler;

    public PerformanceReportExporter(FakePlayerPlugin plugin, PerformanceMonitor monitor, FppProfiler profiler) {
        this.plugin = plugin;
        this.monitor = monitor;
        this.profiler = profiler;
    }

    public File exportBenchmark(int minutes, String label, List<ReportSnapshot.SectionEntry> tree) {
        File dir = new File(plugin.getDataFolder(), "performance-report");
        File archive = new File(dir, "archive");
        if (!archive.exists() && !archive.mkdirs()) {
            FppLogger.warn("Could not create performance-report/archive directory.");
        }

        PerfSnapshot perf = monitor != null ? monitor.latest() : PerfSnapshot.create(0, 0, 0, 0, 0, 0, 0, 0);
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());

        List<BuiltinFppProfiler.FlatEntry> flat = List.of();
        long totalWindowNanos = 0;
        if (profiler instanceof BuiltinFppProfiler builtin) {
            flat = builtin.flatSnapshot(minutes);
            totalWindowNanos = flat.stream()
                    .mapToLong(BuiltinFppProfiler.FlatEntry::totalNanos)
                    .sum();
        }

        StringBuilder sb = new StringBuilder();
        banner(sb, "FakePlayerPlugin Benchmark Report", 78);
        sb.append("Session: ").append(label).append("\n");
        sb.append("Generated: ").append(formatNow()).append("\n");
        sb.append("Duration: ").append(minutes).append(" minute(s)\n");
        sb.append("\n");

        serverMetrics(sb, perf, minutes);
        runtimeDetails(sb);
        configSnapshot(sb, profiler);
        callTree(sb, tree);
        topHotspots(sb, flat, totalWindowNanos, 50);
        moduleBreakdown(sb, tree, totalWindowNanos);
        classBreakdown(sb, tree, totalWindowNanos, 30);
        methodBreakdown(sb, flat, totalWindowNanos, 30);
        events(sb, profiler.eventCounters());
        warnings(sb, perf);
        summary(sb, perf);
        banner(sb, "End of Report", 78);

        String content = sb.toString();
        File latest = new File(dir, "latest.txt");
        File dated = new File(dir, "benchmark-" + timestamp + ".txt");
        File archived = new File(archive, "benchmark-" + timestamp + ".txt");

        write(latest, content);
        write(dated, content);
        write(archived, content);
        return latest;
    }

    public File export(int minutes, boolean includeMethods) {
        File dir = new File(plugin.getDataFolder(), "performance-report");
        File archive = new File(dir, "archive");
        if (!archive.exists() && !archive.mkdirs()) {
            FppLogger.warn("Could not create performance-report/archive directory.");
        }

        ReportSnapshot report = profiler.snapshot(minutes, includeMethods);
        PerfSnapshot perf = monitor != null ? monitor.latest() : PerfSnapshot.create(0, 0, 0, 0, 0, 0, 0, 0);

        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        StringBuilder sb = new StringBuilder();
        banner(sb, "FakePlayerPlugin Performance Report", 78);
        metadata(sb, minutes, perf);
        serverMetrics(sb, perf, minutes);
        fppBreakdown(sb, report);
        topMethods(sb, report);
        events(sb, report.eventCounters());
        warnings(sb, perf);
        summary(sb, perf);
        banner(sb, "End of Report", 78);

        String content = sb.toString();
        File latest = new File(dir, "latest.txt");
        File dated = new File(dir, "performance-" + timestamp + ".txt");
        File archived = new File(archive, "performance-" + timestamp + ".txt");

        write(latest, content);
        write(dated, content);
        write(archived, content);
        return latest;
    }

    private void banner(StringBuilder sb, String title, int width) {
        sb.append(repeat('=', width)).append("\n");
        int pad = Math.max(0, width - title.length()) / 2;
        sb.append(repeat(' ', pad)).append(title).append("\n");
        sb.append(repeat('=', width)).append("\n");
        sb.append("\n");
    }

    private void metadata(StringBuilder sb, int minutes, PerfSnapshot perf) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        sdf.setTimeZone(TimeZone.getDefault());
        sb.append("Generated: ").append(sdf.format(Date.from(perf.timestamp()))).append("\n");
        sb.append("Duration: Last ").append(minutes).append(" minute(s)\n");
        sb.append("Performance Score: ")
                .append(perf.healthScore())
                .append("/100 (")
                .append(perf.healthLabel())
                .append(")\n");
        sb.append("\n");
    }

    private void serverMetrics(StringBuilder sb, PerfSnapshot perf, int minutes) {
        section(sb, "SERVER");
        sb.append("TPS\n");
        sb.append("  Current: ").append(fmt(perf.tps())).append("\n");
        sb.append("\n");
        sb.append("MSPT\n");
        sb.append("  Mean: ").append(fmt(perf.msptMean())).append(" ms\n");
        sb.append("  P95:  ").append(fmt(perf.mspt95())).append(" ms\n");
        sb.append("\n");
        sb.append("Memory\n");
        sb.append("  Used: ")
                .append(perf.usedMemoryMb())
                .append(" MB / ")
                .append(perf.committedMemoryMb())
                .append(" MB committed\n");
        sb.append("\n");
        sb.append("CPU\n");
        sb.append("  Process: ").append(fmt(perf.cpuProcess())).append("%\n");
        sb.append("  System:  ").append(fmt(perf.cpuSystem())).append("%\n");
        sb.append("\n");
        sb.append("GC\n");
        sb.append("  Avg time:     ").append(fmt(perf.gcAvgTime())).append(" ms\n");
        sb.append("  Avg frequency: ").append(perf.gcAvgFrequency()).append(" ms\n");
        sb.append("\n");
        sb.append("Entities\n");
        sb.append("  Players:  ").append(perf.onlinePlayers()).append("\n");
        sb.append("  Bots:     ").append(perf.botCount()).append("\n");
        sb.append("  Total:    ").append(perf.entityCount()).append("\n");
        sb.append("\n");
    }

    private void runtimeDetails(StringBuilder sb) {
        section(sb, "RUNTIME DETAILS");
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();

        sb.append("Uptime: ")
                .append(TimeUnit.MILLISECONDS.toSeconds(runtime.getUptime()))
                .append(" seconds\n");
        sb.append("JVM: ")
                .append(runtime.getVmName())
                .append(" ")
                .append(runtime.getVmVersion())
                .append("\n");
        sb.append("Java: ").append(System.getProperty("java.version")).append("\n");
        sb.append("Heap: ")
                .append(heap.getUsed() / 1_048_576L)
                .append(" MB used / ")
                .append(heap.getCommitted() / 1_048_576L)
                .append(" MB committed / ")
                .append(heap.getMax() / 1_048_576L)
                .append(" MB max\n");
        sb.append("Non-heap: ")
                .append(nonHeap.getUsed() / 1_048_576L)
                .append(" MB used / ")
                .append(nonHeap.getCommitted() / 1_048_576L)
                .append(" MB committed\n");

        sb.append("GC collectors:\n");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            sb.append("  ")
                    .append(gc.getName())
                    .append(": ")
                    .append(gc.getCollectionCount())
                    .append(" collections, ")
                    .append(TimeUnit.MILLISECONDS.toMillis(gc.getCollectionTime()))
                    .append(" ms total\n");
        }

        sb.append("Worlds:\n");
        for (World world : Bukkit.getWorlds()) {
            sb.append("  ")
                    .append(world.getName())
                    .append(": ")
                    .append(world.getLoadedChunks().length)
                    .append(" loaded chunks, ")
                    .append(world.getEntities().size())
                    .append(" entities\n");
        }
        sb.append("\n");
    }

    private void configSnapshot(StringBuilder sb, FppProfiler activeProfiler) {
        section(sb, "CONFIG SNAPSHOT");
        sb.append("performance.enabled: ").append(Config.performanceEnabled()).append("\n");
        sb.append("performance.self-profiler.enabled: ")
                .append(Config.performanceSelfProfilerEnabled())
                .append("\n");
        sb.append("performance.self-profiler.method-level: ")
                .append(Config.performanceSelfProfilerMethodLevel())
                .append("\n");
        sb.append("Profiler active during benchmark: ")
                .append(activeProfiler.isActive())
                .append("\n");
        if (activeProfiler instanceof BuiltinFppProfiler builtin) {
            sb.append("Method-level profiling active: ")
                    .append(builtin.isMethodLevelEnabled())
                    .append("\n");
        }
        sb.append("head-ai.enabled: ").append(Config.headAiEnabled()).append("\n");
        sb.append("fall-damage.enabled: ").append(Config.fallDamageEnabled()).append("\n");
        sb.append("tab-list.enabled: ").append(Config.tabListEnabled()).append("\n");
        sb.append("database.enabled: ").append(Config.databaseEnabled()).append("\n");
        sb.append("network-mode: ").append(Config.isNetworkMode()).append("\n");
        sb.append("max-bots: ").append(Config.maxBots()).append("\n");
        sb.append("\n");
    }

    private void configSnapshot(StringBuilder sb) {
        configSnapshot(sb, profiler);
    }

    private void callTree(StringBuilder sb, List<ReportSnapshot.SectionEntry> tree) {
        section(sb, "FULL CALL TREE");
        if (tree.isEmpty()) {
            sb.append("No self-profiler samples collected yet.\n");
            sb.append("Ensure performance.self-profiler.enabled is true.\n\n");
            return;
        }

        long totalWindowNanos =
                tree.stream().mapToLong(ReportSnapshot.SectionEntry::totalNanos).sum();
        headerRow(sb, "Name", "total%", "total ms", "self%", "self ms", "samples");
        for (ReportSnapshot.SectionEntry e : tree) {
            renderNode(sb, e, 0, totalWindowNanos, totalWindowNanos);
        }
        sb.append("\n");
    }

    private void renderNode(
            StringBuilder sb,
            ReportSnapshot.SectionEntry node,
            int depth,
            long parentTotalNanos,
            long totalWindowNanos) {
        String fullName = node.name();
        String shortName = fullName;
        int lastDot = fullName.lastIndexOf('.');
        if (lastDot >= 0) {
            shortName = fullName.substring(lastDot + 1);
        }

        double pctTotal = parentTotalNanos > 0 ? (node.totalNanos() * 100.0) / parentTotalNanos : 0.0;
        double pctSelf = totalWindowNanos > 0 ? (node.selfNanos() * 100.0) / totalWindowNanos : 0.0;
        long totalMs = Math.round(node.totalNanos() / 1_000_000.0);
        long selfMs = Math.round(node.selfNanos() / 1_000_000.0);

        String indent = "  ".repeat(depth);
        dataRow(sb, indent + shortName, pctTotal, totalMs, pctSelf, selfMs, node.samples(), depth);

        List<ReportSnapshot.SectionEntry> children = new ArrayList<>(node.children());
        children.sort(Comparator.comparingDouble(ReportSnapshot.SectionEntry::percentOfTotal)
                .reversed());
        for (ReportSnapshot.SectionEntry child : children) {
            renderNode(sb, child, depth + 1, node.totalNanos(), totalWindowNanos);
        }
    }

    private void topHotspots(
            StringBuilder sb, List<BuiltinFppProfiler.FlatEntry> flat, long totalWindowNanos, int limit) {
        section(sb, "TOP SELF-TIME HOTSPOTS");
        if (flat.isEmpty()) {
            sb.append("No samples.\n\n");
            return;
        }
        headerRow(sb, "Name", "self%", "self ms", "total%", "total ms", "samples");
        int count = 0;
        for (BuiltinFppProfiler.FlatEntry e : flat) {
            if (e.selfNanos() <= 0) continue;
            dataRow(
                    sb,
                    e.name(),
                    e.selfPercent(),
                    ms(e.selfNanos()),
                    e.totalPercent(),
                    ms(e.totalNanos()),
                    e.samples(),
                    0);
            if (++count >= limit) break;
        }
        sb.append("\n");
    }

    private void moduleBreakdown(StringBuilder sb, List<ReportSnapshot.SectionEntry> tree, long totalWindowNanos) {
        section(sb, "MODULE BREAKDOWN (by first path segment)");
        if (tree.isEmpty()) {
            sb.append("No samples.\n\n");
            return;
        }

        Map<String, long[]> byModule = new HashMap<>();
        accumulateSelfBySegment(tree, byModule, 1, totalWindowNanos);

        List<Map.Entry<String, long[]>> sorted = byModule.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                .toList();
        headerRow(sb, "Module", "self%", "self ms", "", "", "samples");
        for (Map.Entry<String, long[]> e : sorted) {
            long[] v = e.getValue();
            dataRow(sb, e.getKey(), pct(v[1], totalWindowNanos), ms(v[1]), 0, 0, v[2], 0);
        }
        sb.append("\n");
    }

    private void classBreakdown(
            StringBuilder sb, List<ReportSnapshot.SectionEntry> tree, long totalWindowNanos, int limit) {
        section(sb, "DEPTH-2 BREAKDOWN (top " + limit + ")");
        if (tree.isEmpty()) {
            sb.append("No samples.\n\n");
            return;
        }

        Map<String, long[]> byClass = new HashMap<>();
        accumulateSelfBySegment(tree, byClass, 2, totalWindowNanos);

        List<Map.Entry<String, long[]>> sorted = byClass.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                .limit(limit)
                .toList();
        headerRow(sb, "Depth-2", "self%", "self ms", "", "", "samples");
        for (Map.Entry<String, long[]> e : sorted) {
            long[] v = e.getValue();
            dataRow(sb, e.getKey(), pct(v[1], totalWindowNanos), ms(v[1]), 0, 0, v[2], 0);
        }
        sb.append("\n");
    }

    private void accumulateSelfBySegment(
            List<ReportSnapshot.SectionEntry> nodes, Map<String, long[]> out, int segments, long totalWindowNanos) {
        for (ReportSnapshot.SectionEntry node : nodes) {
            String key = segmentPrefix(node.name(), segments);
            if (key != null) {
                long[] acc = out.computeIfAbsent(key, k -> new long[3]);
                acc[1] += node.selfNanos();
                acc[2] += node.samples();
            }
            accumulateSelfBySegment(node.children(), out, segments, totalWindowNanos);
        }
    }

    private String segmentPrefix(String path, int segments) {
        if (path == null || path.isEmpty()) return null;
        int found = 0;
        int end = -1;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '.') {
                found++;
                if (found == segments) {
                    end = i;
                    break;
                }
            }
        }
        return end < 0 ? path : path.substring(0, end);
    }

    private void methodBreakdown(
            StringBuilder sb, List<BuiltinFppProfiler.FlatEntry> flat, long totalWindowNanos, int limit) {
        section(sb, "METHOD BREAKDOWN (top " + limit + ")");
        if (flat.isEmpty()) {
            sb.append("No samples.\n\n");
            return;
        }

        List<BuiltinFppProfiler.FlatEntry> methods = flat.stream()
                .filter(e -> isMethodLike(e.name()))
                .sorted(Comparator.comparingDouble(BuiltinFppProfiler.FlatEntry::selfPercent)
                        .reversed())
                .limit(limit)
                .toList();
        headerRow(sb, "Method", "self%", "self ms", "total%", "total ms", "samples");
        for (BuiltinFppProfiler.FlatEntry e : methods) {
            dataRow(
                    sb,
                    e.name(),
                    e.selfPercent(),
                    ms(e.selfNanos()),
                    e.totalPercent(),
                    ms(e.totalNanos()),
                    e.samples(),
                    0);
        }
        sb.append("\n");
    }

    private void fppBreakdown(StringBuilder sb, ReportSnapshot report) {
        section(sb, "FPP BREAKDOWN");
        List<ReportSnapshot.SectionEntry> all = report.allSections();
        if (all.isEmpty()) {
            sb.append("No self-profiler samples collected yet.\n");
            sb.append("Enable with performance.self-profiler.enabled: true in config.yml.\n\n");
            return;
        }

        headerRow(sb, "Section", "self%", "self ms", "total%", "total ms", "samples");
        for (ReportSnapshot.SectionEntry e : report.coreSections()) {
            dataRow(
                    sb,
                    e.name(),
                    e.percentOfTotal(),
                    ms(e.selfNanos()),
                    e.percentOfTotal(),
                    ms(e.totalNanos()),
                    e.samples(),
                    0);
            for (ReportSnapshot.SectionEntry child : e.children()) {
                dataRow(
                        sb,
                        "  " + child.name(),
                        child.percentOfTotal(),
                        ms(child.selfNanos()),
                        child.percentOfTotal(),
                        ms(child.totalNanos()),
                        child.samples(),
                        1);
            }
        }
        sb.append("\n");
    }

    private void topMethods(StringBuilder sb, ReportSnapshot report) {
        section(sb, "TOP METHODS");
        List<ReportSnapshot.SectionEntry> methods = report.topMethods();
        if (methods.isEmpty()) {
            sb.append("Method-level profiling is disabled or no method samples collected.\n");
            sb.append("Enable with performance.self-profiler.method-level: true in config.yml.\n");
        } else {
            headerRow(sb, "Method", "self%", "self ms", "", "", "samples");
            int rank = 1;
            for (ReportSnapshot.SectionEntry e : methods) {
                dataRow(sb, rank++ + ". " + e.name(), e.percentOfTotal(), ms(e.selfNanos()), 0, 0, e.samples(), 0);
            }
        }
        sb.append("\n");
    }

    private void events(StringBuilder sb, Map<String, Long> counters) {
        section(sb, "EVENTS");
        if (counters.isEmpty()) {
            sb.append("No event counters recorded.\n");
        } else {
            List<Map.Entry<String, Long>> sorted = counters.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                    .toList();
            headerRow(sb, "Event", "", "", "", "", "count");
            for (Map.Entry<String, Long> e : sorted) {
                dataRow(sb, e.getKey(), 0, 0, 0, 0, e.getValue(), 0);
            }
        }
        sb.append("\n");
    }

    private void warnings(StringBuilder sb, PerfSnapshot perf) {
        section(sb, "WARNINGS");
        boolean any = false;
        if (perf.tps() > 0 && perf.tps() < 18.0) {
            sb.append("[WARNING] TPS is low: ").append(fmt(perf.tps())).append("\n");
            any = true;
        }
        if (perf.msptMean() > 50.0) {
            sb.append("[WARNING] MSPT is high: ").append(fmt(perf.msptMean())).append(" ms\n");
            any = true;
        }
        if (perf.cpuProcess() > 80.0) {
            sb.append("[WARNING] Process CPU is high: ")
                    .append(fmt(perf.cpuProcess()))
                    .append("%\n");
            any = true;
        }
        if (perf.gcAvgTime() > 50.0) {
            sb.append("[WARNING] GC avg time is high: ")
                    .append(fmt(perf.gcAvgTime()))
                    .append(" ms\n");
            any = true;
        }
        if (!any) {
            sb.append("No significant bottlenecks detected.\n");
        }
        sb.append("\n");
    }

    private void summary(StringBuilder sb, PerfSnapshot perf) {
        section(sb, "SUMMARY");
        sb.append("FPP Version: ").append(plugin.getPluginMeta().getVersion()).append("\n");
        sb.append("Build ID: ")
                .append(me.bill.fakePlayerPlugin.util.BuildInfo.summary())
                .append("\n");
        sb.append("Server: ")
                .append(Bukkit.getName())
                .append(" ")
                .append(Bukkit.getVersion())
                .append("\n");
        sb.append("Java: ").append(System.getProperty("java.version")).append("\n");
        sb.append("OS: ")
                .append(System.getProperty("os.name"))
                .append(" ")
                .append(System.getProperty("os.version"))
                .append("\n");
        sb.append("Profiler: ").append(profiler.name()).append("\n");
        sb.append("Health score: ")
                .append(perf.healthScore())
                .append("/100 (")
                .append(perf.healthLabel())
                .append(")\n");
        sb.append("\n");
    }

    private void section(StringBuilder sb, String title) {
        sb.append("--------------------------------------------------------------\n");
        sb.append(title).append("\n");
        sb.append("--------------------------------------------------------------\n");
        sb.append("\n");
    }

    private void headerRow(
            StringBuilder sb, String name, String pct1, String ms1, String pct2, String ms2, String samples) {
        sb.append(pad(name, NAME_WIDTH))
                .append("  ")
                .append(pad(pct1, PERCENT_WIDTH))
                .append("  ")
                .append(pad(ms1, MS_WIDTH))
                .append("  ")
                .append(pad(pct2, PERCENT_WIDTH))
                .append("  ")
                .append(pad(ms2, MS_WIDTH))
                .append("  ")
                .append(pad(samples, SAMPLES_WIDTH))
                .append("\n");
        sb.append(repeat('-', NAME_WIDTH + PERCENT_WIDTH + MS_WIDTH + PERCENT_WIDTH + MS_WIDTH + SAMPLES_WIDTH + 10))
                .append("\n");
    }

    private void dataRow(
            StringBuilder sb, String name, double pct1, long ms1, double pct2, long ms2, long samples, int depth) {
        String pct1Str = pct1 > 0 ? fmt(pct1) + "%" : "";
        String pct2Str = pct2 > 0 ? fmt(pct2) + "%" : "";
        String ms1Str = ms1 > 0 ? String.valueOf(ms1) : "";
        String ms2Str = ms2 > 0 ? String.valueOf(ms2) : "";
        String samplesStr = samples > 0 ? String.valueOf(samples) : "";
        sb.append(pad(name, NAME_WIDTH))
                .append("  ")
                .append(pad(pct1Str, PERCENT_WIDTH))
                .append("  ")
                .append(pad(ms1Str, MS_WIDTH))
                .append("  ")
                .append(pad(pct2Str, PERCENT_WIDTH))
                .append("  ")
                .append(pad(ms2Str, MS_WIDTH))
                .append("  ")
                .append(pad(samplesStr, SAMPLES_WIDTH));
        if (pct1 > 0) sb.append("  ").append(bar(pct1));
        sb.append("\n");
    }

    private boolean isMethodLike(String path) {
        int lastDot = path.lastIndexOf('.');
        if (lastDot < 0) return false;
        String last = path.substring(lastDot + 1);
        return last.contains("(") || last.contains("<") || Character.isLowerCase(last.charAt(0));
    }

    private double pct(long nanos, long total) {
        return total > 0 ? (nanos * 100.0) / total : 0.0;
    }

    private long ms(long nanos) {
        return Math.round(nanos / 1_000_000.0);
    }

    private String bar(double percent) {
        int width = (int) Math.round(percent / 2.0);
        if (width <= 0) return "";
        if (width > 25) width = 25;
        return "[" + repeat('█', width) + repeat('░', 25 - width) + "]";
    }

    private static String pad(String text, int width) {
        if (text == null) text = "";
        if (text.length() >= width) return text.substring(0, width);
        StringBuilder sb = new StringBuilder(width);
        sb.append(text);
        for (int i = text.length(); i < width; i++) sb.append(' ');
        return sb.toString();
    }

    private static String repeat(char c, int count) {
        if (count <= 0) return "";
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }

    private static String fmt(double value) {
        if (value < 0 || Double.isNaN(value) || Double.isInfinite(value)) return "N/A";
        return String.format(Locale.US, "%.2f", value);
    }

    private static String formatNow() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private static void write(File file, String content) {
        try (PrintWriter writer = new PrintWriter(file, StandardCharsets.UTF_8)) {
            writer.print(content);
        } catch (IOException e) {
            FppLogger.warn("Failed to write performance report to " + file.getPath() + ": " + e.getMessage());
        }
    }
}
