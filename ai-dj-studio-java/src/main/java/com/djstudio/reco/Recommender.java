package com.djstudio.reco;

import com.djstudio.core.CamelotWheel;
import com.djstudio.core.TrackFeatures;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Hybrid track recommender (PRD Epic 3): content-based similarity to the listener's taste profile combined with
 * item-item collaborative filtering over co-listening.
 *
 * <ul>
 *   <li><b>Content</b>: weighted blend of BPM, energy, valence and Camelot-key closeness to the centroid of the
 *       tracks the user engaged with (weighted by how strongly: likes &gt; completions &gt; brief plays).</li>
 *   <li><b>CF</b>: cosine similarity between tracks over which users engaged with them; a candidate scores high if
 *       people who liked the user's tracks also liked it.</li>
 *   <li><b>Hybrid</b>: {@code w*cf + (1-w)*content} with {@code w = 0.6 * min(1, interactions/3)}, so a brand-new
 *       user relies on content and an established one leans on co-listening. A user with no history gets the most-popular tracks (cold start).</li>
 * </ul>
 * Pure and deterministic: ties are broken by track id.
 */
public final class Recommender {

    public static final double CF_WEIGHT = 0.6;
    public static final int FULL_CONFIDENCE_AFTER = 3;
    private static final double PAIR_WEIGHT_CAP = 5.0;

    private final Map<String, TrackFeatures> catalog;
    private final Map<String, Map<String, Double>> byUser = new HashMap<>();     // user -> track -> weight
    private final Map<String, Map<String, Double>> byTrack = new HashMap<>();    // track -> user -> weight
    private final Map<String, Double> norm = new HashMap<>();

    public Recommender(Map<String, TrackFeatures> catalog, List<Interaction> interactions) {
        this.catalog = catalog;
        for (Interaction i : interactions) {
            if (!catalog.containsKey(i.trackId())) {
                continue;
            }
            byUser.computeIfAbsent(i.userId(), k -> new HashMap<>()).merge(i.trackId(), i.weight(), Double::sum);
        }
        for (var u : byUser.entrySet()) {
            for (var t : u.getValue().entrySet()) {
                double w = Math.min(PAIR_WEIGHT_CAP, t.getValue());
                t.setValue(w);
                byTrack.computeIfAbsent(t.getKey(), k -> new HashMap<>()).put(u.getKey(), w);
            }
        }
        for (var t : byTrack.entrySet()) {
            double sq = 0;
            for (double w : t.getValue().values()) {
                sq += w * w;
            }
            norm.put(t.getKey(), Math.sqrt(sq));
        }
    }

    public List<Recommendation> recommend(String userId, int k) {
        Map<String, Double> mine = byUser.getOrDefault(userId, Map.of());
        if (mine.isEmpty()) {
            return popular(k);
        }
        Profile profile = profile(mine);
        double confidence = Math.min(1.0, (double) mine.size() / FULL_CONFIDENCE_AFTER);
        double weightSum = mine.values().stream().mapToDouble(Double::doubleValue).sum();

        List<Recommendation> out = new ArrayList<>();
        for (TrackFeatures cand : catalog.values()) {
            if (mine.containsKey(cand.id())) {
                continue;
            }
            double content = content(profile, cand);
            double cf = 0;
            for (var item : mine.entrySet()) {
                cf += item.getValue() * cosine(item.getKey(), cand.id());
            }
            cf /= weightSum;
            boolean hasCf = cf > 0;
            double w = CF_WEIGHT * confidence;     // the same blend for every candidate, so scores stay comparable
            double score = w * cf + (1 - w) * content;
            String source = hasCf && cf >= 0.5 ? "similar_listeners" : "taste_match";
            out.add(new Recommendation(cand.id(), round(score), round(cf), round(content), source));
        }
        out.sort(Comparator.comparingDouble(Recommendation::score).reversed().thenComparing(Recommendation::trackId));
        return out.size() > k ? new ArrayList<>(out.subList(0, k)) : out;
    }

    // ------------------------------------------------------------------ cold start

    private List<Recommendation> popular(int k) {
        Map<String, Double> total = new TreeMap<>();
        for (var t : byTrack.entrySet()) {
            total.put(t.getKey(), t.getValue().values().stream().mapToDouble(Double::doubleValue).sum());
        }
        double max = total.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        List<Recommendation> out = new ArrayList<>();
        for (var e : total.entrySet()) {
            out.add(new Recommendation(e.getKey(), round(e.getValue() / max), 0, 0, "popular"));
        }
        out.sort(Comparator.comparingDouble(Recommendation::score).reversed().thenComparing(Recommendation::trackId));
        return out.size() > k ? new ArrayList<>(out.subList(0, k)) : out;
    }

    // ------------------------------------------------------------------ content-based

    private record Profile(double bpm, double energy, double valence, Map<String, Double> keys) {
    }

    private Profile profile(Map<String, Double> mine) {
        double w = 0;
        double bpm = 0;
        double energy = 0;
        double valence = 0;
        Map<String, Double> keys = new HashMap<>();
        for (var e : mine.entrySet()) {
            TrackFeatures t = catalog.get(e.getKey());
            double wt = e.getValue();
            w += wt;
            bpm += wt * t.bpm();
            energy += wt * t.energy();
            valence += wt * t.valence();
            keys.merge(t.camelotKey(), wt, Double::sum);
        }
        return new Profile(bpm / w, energy / w, valence / w, keys);
    }

    static double content(Profile p, TrackFeatures c) {
        double bpmSim = Math.max(0.0, 1.0 - Math.abs(c.bpm() - p.bpm()) / (0.25 * p.bpm()));
        double energySim = 1.0 - Math.abs(c.energy() - p.energy());
        double valenceSim = 1.0 - Math.abs(c.valence() - p.valence());
        double keySim = 0;
        double total = 0;
        for (var k : p.keys().entrySet()) {
            double closeness = CamelotWheel.relation(k.getKey(), c.camelotKey())
                    .map(CamelotWheel.Relation::closeness).orElse(0.1);
            keySim += k.getValue() * closeness;
            total += k.getValue();
        }
        keySim /= total;
        return 0.35 * bpmSim + 0.30 * energySim + 0.20 * valenceSim + 0.15 * keySim;
    }

    // ------------------------------------------------------------------ collaborative

    private double cosine(String a, String b) {
        Map<String, Double> va = byTrack.get(a);
        Map<String, Double> vb = byTrack.get(b);
        if (va == null || vb == null || a.equals(b)) {
            return 0;
        }
        Map<String, Double> small = va.size() <= vb.size() ? va : vb;
        Map<String, Double> large = small == va ? vb : va;
        double dot = 0;
        for (var e : small.entrySet()) {
            Double o = large.get(e.getKey());
            if (o != null) {
                dot += e.getValue() * o;
            }
        }
        return dot == 0 ? 0 : dot / (norm.get(a) * norm.get(b));
    }

    /** Users that have any interaction (handy for evaluation). */
    public Set<String> users() {
        return byUser.keySet();
    }

    private static double round(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
