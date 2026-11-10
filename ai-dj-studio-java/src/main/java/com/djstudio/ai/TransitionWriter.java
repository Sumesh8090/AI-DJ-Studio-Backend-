package com.djstudio.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Writes the short DJ line spoken between two tracks.
 *
 * <p>Grounded by construction: every number comes from the two tracks' metadata or from the matcher's matchReason,
 * and every piece of "lore" is a verbatim curated entry whose id is returned in {@code sourceEvents}. Nothing is
 * generated freely, so the script cannot invent facts (PRD: AI outputs must be grounded in retrieved metadata).
 * Deterministic: the same inputs always produce the same script, which keeps it testable.
 */
public final class TransitionWriter {

    public enum Verbosity {
        QUIET(0), NORMAL(1), CHATTY(2);

        private final int loreEntries;

        Verbosity(int loreEntries) {
            this.loreEntries = loreEntries;
        }

        public static Verbosity parse(String value) {
            if (value != null) {
                for (Verbosity v : values()) {
                    if (v.name().equalsIgnoreCase(value.trim())) {
                        return v;
                    }
                }
            }
            return NORMAL;
        }
    }

    public record Script(String text, List<String> sourceEvents) {
    }

    private static final Map<String, Integer> LORE_PRIORITY = Map.of("sample_credit", 0, "trivia", 1, "glossary", 2);

    public Script write(TrackMeta from, TrackMeta to, Map<String, Object> matchReason, Verbosity verbosity) {
        StringBuilder sb = new StringBuilder();
        sb.append("Easing from ").append(from.title()).append(" into ").append(to.title()).append(": ");
        sb.append(tempoClause(from, to, matchReason)).append(' ');
        sb.append(keyClause(from, to, matchReason)).append(' ');
        sb.append(energyClause(from, to));

        List<String> sources = new ArrayList<>();
        for (MetaEntry e : pickLore(to, verbosity.loreEntries)) {
            sb.append(' ').append(e.text().endsWith(".") ? e.text() : e.text() + ".");
            sources.add(e.citation());
        }
        return new Script(sb.toString().replaceAll("\\s+", " ").trim(), List.copyOf(sources));
    }

    private static String tempoClause(TrackMeta from, TrackMeta to, Map<String, Object> reason) {
        String relation = String.valueOf(reason.getOrDefault("bpmRelation", "direct"));
        String base = String.format(Locale.ROOT, "%s BPM to %s BPM", fmt(from.bpm()), fmt(to.bpm()));
        return switch (relation) {
            case "half_time" -> "the tempo drops to half-time (" + base + ").";
            case "double_time" -> "the tempo doubles up (" + base + ").";
            default -> "the tempo shifts " + base + ".";
        };
    }

    private static String keyClause(TrackMeta from, TrackMeta to, Map<String, Object> reason) {
        String relation = String.valueOf(reason.getOrDefault("camelotRelation", "identical"));
        String keys = from.camelotKey() + " to " + to.camelotKey();
        return switch (relation) {
            case "adjacent" -> "The key moves to a neighbouring Camelot position (" + keys + ").";
            case "relative" -> "The key swaps to its relative major/minor (" + keys + ").";
            default -> "The key stays put (" + keys + ").";
        };
    }

    private static String energyClause(TrackMeta from, TrackMeta to) {
        double d = to.energy() - from.energy();
        if (Math.abs(d) < 0.05) {
            return "Energy holds steady.";
        }
        return d > 0 ? "Energy lifts." : "Energy settles.";
    }

    private static List<MetaEntry> pickLore(TrackMeta to, int count) {
        return to.entries().stream()
                .sorted(Comparator.comparingInt((MetaEntry e) -> LORE_PRIORITY.getOrDefault(e.type(), 9))
                        .thenComparing(MetaEntry::id))
                .limit(count)
                .toList();
    }

    private static String fmt(double bpm) {
        return bpm == Math.rint(bpm) ? String.valueOf((long) bpm) : String.format(Locale.ROOT, "%.1f", bpm);
    }
}
