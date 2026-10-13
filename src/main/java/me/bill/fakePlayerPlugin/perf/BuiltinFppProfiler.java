package me.bill.fakePlayerPlugin.perf;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Built-in lightweight profiler. Uses lock-free per-section LongAdders, a
 * circular buffer of per-sample records, and adaptive detail reduction under
 * heavy load. Safe to call from multiple threads including Folia region threads.
 */
public final class BuiltinFppProfiler implements FppProfiler {

    private static final List<Integer> WINDOWS = List.of(1, 5, 15, 60);
    private static final long SAMPLE_RETENTION_NANOS = TimeUnit.MINUTES.toNanos(60);
    private static final long ADAPTIVE_THRESHOLD_MSPT = 100L;
    private static final long METHOD_LEVEL_DISABLE_NANOS = TimeUnit.MILLISECONDS.toNanos(150L);

    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean methodLevelEnabled = new AtomicBoolean(false);
    private final Map<String, SectionAccumulator> sections = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<SampleRecord> samples = new ConcurrentLinkedDeque<>();
    private final Map<String, LongAdder> eventCounters = new ConcurrentHashMap<>();
    private final AtomicLong totalRecordedNanos = new AtomicLong();
    private final AtomicLong methodLevelDisabledUntil = new AtomicLong(0);
    private final AtomicLong lastPrune = new AtomicLong(System.nanoTime());
    private final AtomicBoolean benchmarkMode = new AtomicBoolean(false);
    private final AtomicLong benchmarkStartedAt = new AtomicLong(0);

    public BuiltinFppProfiler(boolean methodLevel) {
        this.methodLevelEnabled.set(methodLevel);
    }

    @Override
    public String name() {
        return "builtin";
    }

    @Override
    public boolean isActive() {
        return active.get();
    }

    public void start() {
        active.set(true);
    }

    public void stop() {
        active.set(false);
        benchmarkMode.set(false);
        benchmarkStartedAt.set(0);
        sections.clear();
        samples.clear();
        eventCounters.clear();
        totalRecordedNanos.set(0);
    }

    public void startBenchmark() {
        benchmarkStartedAt.set(System.nanoTime());
        benchmarkMode.set(true);
        active.set(true);
        methodLevelEnabled.set(true);
        sections.clear();
        samples.clear();
        eventCounters.clear();
        totalRecordedNanos.set(0);
    }

    public void stopBenchmark() {
        benchmarkMode.set(false);
        benchmarkStartedAt.set(0);
    }

    public long benchmarkElapsedSeconds() {
        long start = benchmarkStartedAt.get();
        if (start <= 0) return 0;
        return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
    }

    private static final ThreadLocal<ArrayDeque<ActiveProfilerToken>> activeStack =
            ThreadLocal.withInitial(ArrayDeque::new);

    ActiveProfilerToken peekActiveToken() {
        return activeStack.get().peekLast();
    }

    void pushActiveToken(ActiveProfilerToken token) {
        activeStack.get().addLast(token);
    }

    void popActiveToken(ActiveProfilerToken token) {
        ArrayDeque<ActiveProfilerToken> stack = activeStack.get();
        if (stack.peekLast() == token) {
            stack.pollLast();
        } else {
            // Mismatched close; drain until found to keep stack sane.
            while (!stack.isEmpty() && stack.pollLast() != token) {
                // drain
            }
        }
    }

    @Override
    public ProfilerToken enter(String section) {
        if (!active.get()) return NoOpProfilerToken.INSTANCE;
        return new ActiveProfilerToken(this, section, false);
    }

    @Override
    public ProfilerToken enterMethod(String section) {
        if (!active.get()) return NoOpProfilerToken.INSTANCE;
        if (!methodLevelEnabled.get()) return NoOpProfilerToken.INSTANCE;
        if (System.nanoTime() < methodLevelDisabledUntil.get()) return NoOpProfilerToken.INSTANCE;
        return new ActiveProfilerToken(this, section, true);
    }

    @Override
    public void incrementEvent(String eventName, long delta) {
        if (!active.get() && delta <= 0) return;
        eventCounters.computeIfAbsent(eventName, k -> new LongAdder()).add(delta);
    }

    @Override
    public void record(String section, long elapsedNanos, long threadUserNanos, boolean methodLevel) {
        if (!active.get()) return;
        if (elapsedNanos <= 0) return;
        if (methodLevel && System.nanoTime() < methodLevelDisabledUntil.get()) return;

        long now = System.nanoTime();
        SectionAccumulator acc = sections.computeIfAbsent(section, SectionAccumulator::new);
        acc.add(elapsedNanos, threadUserNanos, now);

        // Also attribute this elapsed time to every ancestor prefix so the tree
        // shows total time inclusive of children.
        int dot = section.indexOf('.');
        while (dot > 0) {
            String prefix = section.substring(0, dot);
            SectionAccumulator prefixAcc = sections.computeIfAbsent(prefix, SectionAccumulator::new);
            prefixAcc.addTotal(elapsedNanos, now);
            dot = section.indexOf('.', dot + 1);
        }

        samples.addLast(new SampleRecord(section, elapsedNanos, now, methodLevel));
        totalRecordedNanos.addAndGet(elapsedNanos);

        // Adaptive: if a single method-level sample takes too long, disable method-level for 60s.
        if (methodLevel && elapsedNanos > METHOD_LEVEL_DISABLE_NANOS) {
            methodLevelDisabledUntil.set(now + TimeUnit.MINUTES.toNanos(1));
        }

        // Periodic prune (not every sample to reduce allocation churn)
        if (now - lastPrune.get() > TimeUnit.MINUTES.toNanos(1)) {
            prune(now);
        }
    }

    private void prune(long now) {
        lastPrune.set(now);
        long cutoff = now - SAMPLE_RETENTION_NANOS;
        SampleRecord r;
        while ((r = samples.peekFirst()) != null && r.timestamp() < cutoff) {
            samples.pollFirst();
        }
    }

    @Override
    public ReportSnapshot snapshot(int minutes, boolean includeMethods) {
        long now = System.nanoTime();
        prune(now);
        long windowNanos = TimeUnit.MINUTES.toNanos(Math.max(1, minutes));
        long cutoff = now - windowNanos;

        Map<String, Aggregate> aggregates = new HashMap<>();
        long totalWindowNanos = 0;
        for (SampleRecord s : samples) {
            if (s.timestamp() < cutoff) continue;
            Aggregate a = aggregates.computeIfAbsent(s.section(), Aggregate::new);
            a.add(s.elapsedNanos());
            totalWindowNanos += s.elapsedNanos();
        }

        Map<String, ReportSnapshot.SectionEntry> byName = new HashMap<>();
        for (Aggregate a : aggregates.values()) {
            byName.put(a.name, toEntry(a, totalWindowNanos, includeMethods));
        }

        // Build parent/child hierarchy based on dot-separated section names.
        for (Map.Entry<String, ReportSnapshot.SectionEntry> e : new HashMap<>(byName).entrySet()) {
            String name = e.getKey();
            int lastDot = name.lastIndexOf('.');
            if (lastDot <= 0) continue;
            String parent = name.substring(0, lastDot);
            ReportSnapshot.SectionEntry parentEntry = byName.get(parent);
            if (parentEntry == null) continue;
            List<ReportSnapshot.SectionEntry> children = new ArrayList<>(parentEntry.children());
            children.add(e.getValue());
            children.sort(Comparator.comparingDouble(ReportSnapshot.SectionEntry::percentOfTotal)
                    .reversed());
            byName.put(
                    parent,
                    new ReportSnapshot.SectionEntry(
                            parentEntry.name(),
                            parentEntry.category(),
                            parentEntry.totalNanos(),
                            parentEntry.selfNanos(),
                            parentEntry.samples(),
                            parentEntry.percentOfTotal(),
                            parentEntry.methodLevel(),
                            children));
        }

        List<ReportSnapshot.SectionEntry> coreSections = new ArrayList<>();
        List<ReportSnapshot.SectionEntry> extensionSections = new ArrayList<>();
        List<ReportSnapshot.SectionEntry> topMethods = new ArrayList<>();

        for (ReportSnapshot.SectionEntry e : byName.values()) {
            if (e.name().contains(".")) {
                if (e.methodLevel() && includeMethods) {
                    topMethods.add(e);
                }
                continue;
            }
            String name = e.name().toLowerCase(Locale.ROOT);
            if (name.startsWith("fpp-")) {
                extensionSections.add(e);
            } else {
                coreSections.add(e);
            }
        }

        Comparator<ReportSnapshot.SectionEntry> byPercent = Comparator.comparingDouble(
                        ReportSnapshot.SectionEntry::percentOfTotal)
                .reversed();
        coreSections.sort(byPercent);
        extensionSections.sort(byPercent);
        topMethods.sort(byPercent);
        if (topMethods.size() > 20) topMethods = topMethods.subList(0, 20);

        Map<String, Long> counters = new HashMap<>();
        for (Map.Entry<String, LongAdder> e : eventCounters.entrySet()) {
            counters.put(e.getKey(), e.getValue().sum());
        }

        return new ReportSnapshot(
                java.time.Instant.now(),
                minutes,
                PerfSnapshot.create(0, 0, 0, 0, 0, 0, 0, 0),
                coreSections,
                extensionSections,
                topMethods,
                counters);
    }

    public List<FlatEntry> flatSnapshot(int minutes) {
        long now = System.nanoTime();
        prune(now);
        long windowNanos = TimeUnit.MINUTES.toNanos(Math.max(1, minutes));
        long cutoff = now - windowNanos;

        Map<String, TreeAggregate> map = new HashMap<>();
        long totalWindowNanos = 0;
        for (SampleRecord s : samples) {
            if (s.timestamp() < cutoff) continue;
            String full = s.section();
            getOrCreate(map, full).add(s.elapsedNanos(), true);
            totalWindowNanos += s.elapsedNanos();
            int dot = full.indexOf('.');
            while (dot > 0) {
                String prefix = full.substring(0, dot);
                getOrCreate(map, prefix).add(s.elapsedNanos(), false);
                dot = full.indexOf('.', dot + 1);
            }
        }

        // Compute self = total - children total.
        Map<String, Long> childrenTotalByParent = new HashMap<>();
        for (String name : map.keySet()) {
            int dot = name.lastIndexOf('.');
            if (dot <= 0) continue;
            String parent = name.substring(0, dot);
            TreeAggregate agg = map.get(name);
            childrenTotalByParent.merge(parent, agg.totalNanos, Long::sum);
        }

        List<FlatEntry> list = new ArrayList<>();
        for (Map.Entry<String, TreeAggregate> e : map.entrySet()) {
            String name = e.getKey();
            TreeAggregate agg = e.getValue();
            long selfNanos = Math.max(0, agg.totalNanos - childrenTotalByParent.getOrDefault(name, 0L));
            double totalPct = totalWindowNanos > 0 ? (agg.totalNanos * 100.0) / totalWindowNanos : 0.0;
            double selfPct = totalWindowNanos > 0 ? (selfNanos * 100.0) / totalWindowNanos : 0.0;
            list.add(new FlatEntry(name, agg.totalNanos, selfNanos, agg.samples, totalPct, selfPct));
        }
        list.sort(Comparator.comparingDouble(FlatEntry::selfPercent).reversed());
        return list;
    }

    public record FlatEntry(
            String name, long totalNanos, long selfNanos, long samples, double totalPercent, double selfPercent) {}

    /**
     * Build a full call tree from the collected samples. Each sample is stored by its
     * dotted path; ancestor prefixes are also aggregated so total time includes children.
     */
    public List<ReportSnapshot.SectionEntry> snapshotTree(int minutes) {
        long now = System.nanoTime();
        prune(now);
        long windowNanos = TimeUnit.MINUTES.toNanos(Math.max(1, minutes));
        long cutoff = now - windowNanos;

        // Re-aggregate from samples within window to ensure totals are window-correct.
        Map<String, TreeAggregate> map = new HashMap<>();
        long totalWindowNanos = 0;
        for (SampleRecord s : samples) {
            if (s.timestamp() < cutoff) continue;
            String full = s.section();
            getOrCreate(map, full).add(s.elapsedNanos(), true);
            totalWindowNanos += s.elapsedNanos();
            // Ancestors get total-only attribution.
            int dot = full.indexOf('.');
            while (dot > 0) {
                String prefix = full.substring(0, dot);
                getOrCreate(map, prefix).add(s.elapsedNanos(), false);
                dot = full.indexOf('.', dot + 1);
            }
        }

        if (map.isEmpty()) {
            return List.of();
        }

        // Compute self time for each node: total - children total.
        Map<String, List<String>> childrenByParent = new HashMap<>();
        for (String name : map.keySet()) {
            int dot = name.lastIndexOf('.');
            if (dot <= 0) continue;
            String parent = name.substring(0, dot);
            childrenByParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(name);
        }

        List<ReportSnapshot.SectionEntry> roots = new ArrayList<>();
        for (String name : map.keySet()) {
            if (name.indexOf('.') < 0) {
                roots.add(buildTreeEntry(name, map, childrenByParent, totalWindowNanos));
            }
        }
        roots.sort(Comparator.comparingDouble(ReportSnapshot.SectionEntry::percentOfTotal)
                .reversed());
        return roots;
    }

    private static TreeAggregate getOrCreate(Map<String, TreeAggregate> map, String name) {
        return map.computeIfAbsent(name, TreeAggregate::new);
    }

    private static ReportSnapshot.SectionEntry buildTreeEntry(
            String name,
            Map<String, TreeAggregate> map,
            Map<String, List<String>> childrenByParent,
            long totalWindowNanos) {
        TreeAggregate agg = map.getOrDefault(name, new TreeAggregate(name));
        long childrenTotal = 0;
        List<ReportSnapshot.SectionEntry> children = new ArrayList<>();
        List<String> childNames = childrenByParent.getOrDefault(name, List.of());
        for (String childName : childNames) {
            ReportSnapshot.SectionEntry child = buildTreeEntry(childName, map, childrenByParent, totalWindowNanos);
            children.add(child);
            childrenTotal += child.totalNanos();
        }
        children.sort(Comparator.comparingDouble(ReportSnapshot.SectionEntry::percentOfTotal)
                .reversed());
        long selfNanos = Math.max(0, agg.totalNanos - childrenTotal);
        double pct = totalWindowNanos > 0 ? (agg.totalNanos * 100.0) / totalWindowNanos : 0.0;
        boolean methodLevel = name.contains(".") && isMethodLike(name.substring(name.lastIndexOf('.') + 1));
        String category = name.toLowerCase(Locale.ROOT).startsWith("fpp-") ? "extension" : "core";
        return new ReportSnapshot.SectionEntry(
                name, category, agg.totalNanos, selfNanos, agg.samples, pct, methodLevel, children);
    }

    private static boolean isMethodLike(String lastSegment) {
        return lastSegment.contains("(") || lastSegment.contains("<") || Character.isLowerCase(lastSegment.charAt(0));
    }

    private static ReportSnapshot.SectionEntry toEntry(Aggregate a, long totalWindowNanos, boolean includeMethods) {
        double pct = totalWindowNanos > 0 ? (a.totalNanos * 100.0) / totalWindowNanos : 0.0;
        boolean methodLevel = a.name.contains(".")
                && a.name.substring(a.name.lastIndexOf('.') + 1).contains("(");
        String category = "core";
        if (a.name.toLowerCase(Locale.ROOT).startsWith("fpp-")) category = "extension";
        return new ReportSnapshot.SectionEntry(
                a.name, category, a.totalNanos, a.selfNanos, a.samples, pct, methodLevel, List.of());
    }

    @Override
    public List<Integer> supportedWindows() {
        return WINDOWS;
    }

    @Override
    public Map<String, Long> eventCounters() {
        Map<String, Long> result = new HashMap<>();
        for (Map.Entry<String, LongAdder> e : eventCounters.entrySet()) {
            result.put(e.getKey(), e.getValue().sum());
        }
        return result;
    }

    public void setMethodLevelEnabled(boolean enabled) {
        methodLevelEnabled.set(enabled);
    }

    public boolean isMethodLevelEnabled() {
        return methodLevelEnabled.get();
    }

    public void onHeavyLoad(long mspt) {
        if (mspt >= ADAPTIVE_THRESHOLD_MSPT) {
            methodLevelDisabledUntil.set(System.nanoTime() + TimeUnit.MINUTES.toNanos(1));
        }
    }

    private static final class SectionAccumulator {
        final String name;
        final LongAdder totalNanos = new LongAdder();
        final LongAdder selfNanos = new LongAdder();
        final LongAdder samples = new LongAdder();
        final AtomicLong lastUpdate = new AtomicLong();

        SectionAccumulator(String name) {
            this.name = name;
        }

        void addTotal(long elapsedNanos, long now) {
            totalNanos.add(elapsedNanos);
            samples.increment();
            lastUpdate.set(now);
        }

        void add(long elapsedNanos, long threadUserNanos, long now) {
            totalNanos.add(elapsedNanos);
            selfNanos.add(threadUserNanos > 0 ? threadUserNanos : elapsedNanos);
            samples.increment();
            lastUpdate.set(now);
        }
    }

    private record SampleRecord(String section, long elapsedNanos, long timestamp, boolean methodLevel) {}

    private static final class Aggregate {
        final String name;
        long totalNanos;
        long selfNanos;
        long samples;

        Aggregate(String name) {
            this.name = name;
        }

        void add(long elapsedNanos) {
            totalNanos += elapsedNanos;
            selfNanos += elapsedNanos;
            samples++;
        }
    }

    private static final class TreeAggregate {
        final String name;
        long totalNanos;
        long samples;

        TreeAggregate(String name) {
            this.name = name;
        }

        void add(long elapsedNanos, boolean incrementSamples) {
            totalNanos += elapsedNanos;
            if (incrementSamples) samples++;
        }
    }

    private static final class NoOpProfilerToken implements ProfilerToken {
        static final NoOpProfilerToken INSTANCE = new NoOpProfilerToken();

        @Override
        public void close() {}
    }
}
