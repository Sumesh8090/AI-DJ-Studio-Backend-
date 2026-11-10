package com.djstudio.analytics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A listener's acoustic taste (PRD 6.3): preferred BPM range, favourite Camelot keys, typical energy and valence,
 * all weighted by how strongly the listener engaged with each track (likes count more than brief plays).
 * Pure and deterministic.
 */
public final class TasteProfile {

    public record Entry(double bpm, String camelotKey, double energy, double valence, double weight) {
    }

    public record KeyShare(String key, double share) {
    }

    /** bpmLow/bpmHigh are the weighted 10th and 90th percentiles, so one outlier track does not stretch the range. */
    public record Profile(int tracks, double bpmLow, double bpmAverage, double bpmHigh, double averageEnergy,
                          double averageValence, List<KeyShare> topKeys) {
    }

    private TasteProfile() {
    }

    public static Profile of(List<Entry> entries) {
        List<Entry> usable = entries.stream().filter(e -> e.weight() > 0).toList();
        if (usable.isEmpty()) {
            return new Profile(0, 0, 0, 0, 0, 0, List.of());
        }
        double total = 0;
        double bpm = 0;
        double energy = 0;
        double valence = 0;
        Map<String, Double> keys = new HashMap<>();
        for (Entry e : usable) {
            total += e.weight();
            bpm += e.weight() * e.bpm();
            energy += e.weight() * e.energy();
            valence += e.weight() * e.valence();
            keys.merge(e.camelotKey(), e.weight(), Double::sum);
        }
        List<KeyShare> top = new ArrayList<>();
        for (var k : keys.entrySet()) {
            top.add(new KeyShare(k.getKey(), round(k.getValue() / total)));
        }
        top.sort(Comparator.comparingDouble(KeyShare::share).reversed().thenComparing(KeyShare::key));
        return new Profile(usable.size(), round(percentile(usable, total, 0.10)), round(bpm / total),
                round(percentile(usable, total, 0.90)), round(energy / total), round(valence / total),
                top.size() > 3 ? List.copyOf(top.subList(0, 3)) : List.copyOf(top));
    }

    private static double percentile(List<Entry> entries, double total, double q) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingDouble(Entry::bpm));
        double target = q * total;
        double acc = 0;
        for (Entry e : sorted) {
            acc += e.weight();
            if (acc >= target) {
                return e.bpm();
            }
        }
        return sorted.get(sorted.size() - 1).bpm();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
