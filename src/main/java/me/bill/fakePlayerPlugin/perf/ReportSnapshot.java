package me.bill.fakePlayerPlugin.perf;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Aggregated self-profiler report data used by the text exporter.
 */
public record ReportSnapshot(
        Instant generatedAt,
        int windowMinutes,
        PerfSnapshot perf,
        List<SectionEntry> coreSections,
        List<SectionEntry> extensionSections,
        List<SectionEntry> topMethods,
        Map<String, Long> eventCounters) {

    public ReportSnapshot {
        coreSections = List.copyOf(coreSections);
        extensionSections = List.copyOf(extensionSections);
        topMethods = List.copyOf(topMethods);
        eventCounters = Map.copyOf(eventCounters);
    }

    public List<SectionEntry> allSections() {
        List<SectionEntry> all = new ArrayList<>(coreSections.size() + extensionSections.size());
        all.addAll(coreSections);
        all.addAll(extensionSections);
        all.sort(Comparator.comparingDouble(SectionEntry::percentOfTotal).reversed());
        return all;
    }

    public record SectionEntry(
            String name,
            String category,
            long totalNanos,
            long selfNanos,
            long samples,
            double percentOfTotal,
            boolean methodLevel,
            List<SectionEntry> children) {

        public SectionEntry {
            children = children == null ? List.of() : List.copyOf(children);
        }

        public double millis() {
            return totalNanos / 1_000_000.0;
        }
    }
}
