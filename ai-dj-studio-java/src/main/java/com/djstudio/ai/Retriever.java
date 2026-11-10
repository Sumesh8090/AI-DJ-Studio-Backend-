package com.djstudio.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Grounded question answering over curated track metadata (extractive retrieval, no free generation).
 *
 * <p>Ranks timeline entries by keyword overlap (lightly stemmed, so sample/sampled/sampling match) plus intent
 * boosts, and answers by quoting the best entries with their citation ids. Tempo/key/energy questions are answered
 * from the acoustic fields. When nothing relevant is found it says so instead of guessing: {@code grounded=false}.
 */
public final class Retriever {

    public record Answer(String text, boolean grounded, List<String> citations) {
    }

    public static final String DECLINE =
            "I don't have anything in the track notes that answers that, so I'd rather not guess.";

    private static final Set<String> STOP = Set.of("a", "an", "the", "is", "are", "was", "were", "be", "of", "in", "on",
            "to", "for", "and", "or", "it", "this", "that", "what", "who", "which", "how", "why", "does", "do", "did",
            "me", "tell", "about", "any", "there", "with", "track", "song", "playing", "current", "from", "its");

    private static final Set<String> TEMPO = Set.of("bpm", "tempo", "speed", "fast", "slow");
    private static final Set<String> KEY = Set.of("key", "camelot", "harmonic");
    private static final Set<String> ENERGY = Set.of("energy", "energetic", "intense", "intens");

    private record Scored(TrackMeta track, MetaEntry entry, double score) {
    }

    /** @param current the track to default to; @param pool tracks that may be asked about (current + queue). */
    public Answer answer(String question, TrackMeta current, List<TrackMeta> pool) {
        if (question == null || question.isBlank()) {
            return new Answer(DECLINE, false, List.of());
        }
        Set<String> q = tokens(question);
        TrackMeta subject = pickSubject(question, current, pool);
        if (subject == null) {
            return new Answer(DECLINE, false, List.of());
        }

        // acoustic facts, straight from the features
        List<String> parts = new ArrayList<>();
        List<String> cites = new ArrayList<>();
        if (!disjoint(q, TEMPO)) {
            parts.add(subject.title() + " runs at " + trim(subject.bpm()) + " BPM.");
            cites.add("track:" + subject.trackId() + ":bpm");
        }
        if (!disjoint(q, KEY)) {
            parts.add(subject.title() + " is in " + subject.camelotKey() + " on the Camelot wheel.");
            cites.add("track:" + subject.trackId() + ":key");
        }
        if (!disjoint(q, ENERGY)) {
            parts.add(subject.title() + " has an energy level of " + String.format(Locale.ROOT, "%.2f", subject.energy())
                    + " (0 to 1).");
            cites.add("track:" + subject.trackId() + ":energy");
        }
        boolean acousticAsked = !parts.isEmpty();

        // timeline entries
        List<Scored> hits = new ArrayList<>();
        for (MetaEntry e : subject.entries()) {
            double s = score(q, e);
            if (s >= 1.0) {
                hits.add(new Scored(subject, e, s));
            }
        }
        hits.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(h -> h.entry().id()));
        if (!hits.isEmpty() && !acousticAsked) {
            double best = hits.get(0).score();
            int taken = 0;
            for (Scored h : hits) {
                if (taken == 2 || h.score() < best * 0.6) {
                    break;
                }
                String t = h.entry().text();
                parts.add(t.endsWith(".") ? t : t + ".");
                cites.add(h.entry().citation());
                taken++;
            }
        }
        if (parts.isEmpty()) {
            return new Answer(DECLINE, false, List.of());
        }
        return new Answer("About " + subject.title() + ": " + String.join(" ", parts), true, List.copyOf(cites));
    }

    private static double score(Set<String> q, MetaEntry e) {
        Set<String> doc = tokens(e.text() + " " + e.type().replace('_', ' '));
        double s = 0;
        for (String t : q) {
            if (doc.contains(t)) {
                s += 1.0;
            }
        }
        return s;
    }

    private static TrackMeta pickSubject(String question, TrackMeta current, List<TrackMeta> pool) {
        for (TrackMeta t : pool) {
            if (mentionsTitle(question, t)) {
                return t;
            }
        }
        return current;
    }

    private static boolean mentionsTitle(String question, TrackMeta t) {
        return t.title() != null && !t.title().isBlank()
                && question.toLowerCase(Locale.ROOT).contains(t.title().toLowerCase(Locale.ROOT));
    }

    static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (raw.isEmpty() || STOP.contains(raw)) {
                continue;
            }
            out.add(stem(raw));
        }
        return out;
    }

    static String stem(String w) {
        if (w.length() > 5 && w.endsWith("ing")) {
            w = w.substring(0, w.length() - 3);
        } else if (w.length() > 4 && w.endsWith("ed")) {
            w = w.substring(0, w.length() - 2);
        } else if (w.length() > 4 && w.endsWith("es")) {
            w = w.substring(0, w.length() - 2);
        } else if (w.length() > 3 && w.endsWith("s")) {
            w = w.substring(0, w.length() - 1);
        }
        if (w.length() > 3 && w.endsWith("e")) {
            w = w.substring(0, w.length() - 1);
        }
        return w;
    }

    private static boolean disjoint(Set<String> a, Set<String> b) {
        for (String s : a) {
            if (b.contains(s)) {
                return false;
            }
        }
        return true;
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
