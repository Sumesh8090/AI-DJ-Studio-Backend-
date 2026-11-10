package com.djstudio.reco;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djstudio.core.TrackFeatures;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecommenderTest {

    static TrackFeatures t(String id, double bpm, String key, double energy, double valence) {
        return new TrackFeatures(id, bpm, key, energy, valence, id);
    }

    static Interaction like(String user, String track) {
        return new Interaction(user, track, 200, true, true);
    }

    // ------------------------------------------------------------ behaviour

    @Test
    void neverRecommendsWhatTheUserAlreadyHeard() {
        Map<String, TrackFeatures> cat = Map.of("a", t("a", 124, "8A", .7, .6), "b", t("b", 126, "8A", .7, .6),
                "c", t("c", 128, "9A", .7, .6));
        Recommender r = new Recommender(cat, List.of(like("u", "a"), like("u", "b")));
        List<Recommendation> recs = r.recommend("u", 5);
        assertEquals(1, recs.size());
        assertEquals("c", recs.get(0).trackId());
    }

    @Test
    void contentSimilarityPrefersTracksCloseToTheUsersTaste() {
        Map<String, TrackFeatures> cat = new HashMap<>();
        cat.put("liked", t("liked", 124, "8A", .8, .7));
        cat.put("near", t("near", 126, "8A", .8, .7));
        cat.put("far", t("far", 70, "2B", .2, .1));
        Recommender r = new Recommender(cat, List.of(like("u", "liked")));
        List<Recommendation> recs = r.recommend("u", 2);
        assertEquals("near", recs.get(0).trackId());
        assertTrue(recs.get(0).score() > recs.get(1).score());
    }

    @Test
    void collaborativeSignalLiftsTracksLikedByLookalikeListeners() {
        // content features are identical, so only co-listening can separate "z" (liked by fans of the seed) from
        // "a" (liked by someone unrelated). "a" sorts first by id, so a tie would wrongly put it on top.
        Map<String, TrackFeatures> cat = new HashMap<>();
        for (String id : List.of("seed", "z", "a")) {
            cat.put(id, t(id, 124, "8A", .7, .6));
        }
        List<Interaction> data = new ArrayList<>();
        data.add(like("me", "seed"));
        for (int i = 0; i < 5; i++) {
            data.add(like("fan" + i, "seed"));
            data.add(like("fan" + i, "z"));
        }
        data.add(like("other", "a"));
        List<Recommendation> recs = new Recommender(cat, data).recommend("me", 2);
        assertEquals("z", recs.get(0).trackId());
        assertEquals("similar_listeners", recs.get(0).source());
        assertTrue(recs.get(0).cf() > recs.get(1).cf());
        assertTrue(recs.get(0).score() > recs.get(1).score());
    }

    @Test
    void coldStartUserGetsPopularTracks() {
        Map<String, TrackFeatures> cat = Map.of("hit", t("hit", 124, "8A", .7, .6), "miss", t("miss", 90, "3B", .3, .3));
        List<Interaction> data = List.of(like("a", "hit"), like("b", "hit"), like("c", "miss"));
        List<Recommendation> recs = new Recommender(cat, data).recommend("newbie", 5);
        assertEquals("hit", recs.get(0).trackId());
        assertEquals("popular", recs.get(0).source());
    }

    @Test
    void emptyDataGivesNoRecommendations() {
        assertTrue(new Recommender(Map.of("a", t("a", 120, "8A", .5, .5)), List.of()).recommend("u", 5).isEmpty());
    }

    @Test
    void likesOutweighBriefPlays() {
        assertTrue(like("u", "t").weight() > new Interaction("u", "t", 200, true, false).weight());
        assertTrue(new Interaction("u", "t", 200, true, false).weight() > new Interaction("u", "t", 40, false, false).weight());
        assertTrue(new Interaction("u", "t", 40, false, false).weight() > new Interaction("u", "t", 5, false, false).weight());
    }

    @Test
    void unknownTracksInInteractionsAreIgnoredAndOutputIsDeterministic() {
        Map<String, TrackFeatures> cat = Map.of("a", t("a", 124, "8A", .7, .6), "b", t("b", 124, "8A", .7, .6));
        List<Interaction> data = List.of(like("u", "a"), like("u", "ghost"));
        List<Recommendation> r1 = new Recommender(cat, data).recommend("u", 5);
        assertEquals(r1, new Recommender(cat, data).recommend("u", 5));
        assertEquals("b", r1.get(0).trackId());
    }

    @Test
    void limitIsRespected() {
        Map<String, TrackFeatures> cat = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            cat.put("t" + i, t("t" + i, 120 + i, "8A", .5, .5));
        }
        assertEquals(3, new Recommender(cat, List.of(like("u", "t0"))).recommend("u", 3).size());
    }

    // ------------------------------------------------------------ offline evaluation: Precision@5

    /**
     * Synthetic world: 3 "genres" (distinct BPM/energy/valence/key regions) of 12 tracks each. 36 users each prefer one
     * genre and like 8 of its tracks (15% of their likes are noise from other genres). We hide nothing from the
     * recommender's catalogue but measure whether its top-5 for each user lands in the user's preferred genre among
     * unseen tracks. Compared with a popularity baseline and a random baseline.
     *
     * This checks the algorithm's mechanics on controlled data. It is not a claim about real listeners.
     */
    @Test
    void precisionAt5BeatsPopularityAndRandomBaselines() {
        Random rnd = new Random(42);
        double[][] centre = {{124, .80, .70}, {90, .40, .30}, {150, .90, .50}};
        String[] keys = {"8A", "3B", "11A"};
        Map<String, TrackFeatures> cat = new HashMap<>();
        Map<String, Integer> genre = new HashMap<>();
        List<List<String>> byGenre = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (int g = 0; g < 3; g++) {
            for (int i = 0; i < 12; i++) {
                String id = "g" + g + "_" + i;
                double bpm = centre[g][0] + rnd.nextGaussian() * 4;
                double e = clamp(centre[g][1] + rnd.nextGaussian() * 0.06);
                double v = clamp(centre[g][2] + rnd.nextGaussian() * 0.06);
                cat.put(id, t(id, bpm, keys[g], e, v));
                genre.put(id, g);
                byGenre.get(g).add(id);
            }
        }
        // popularity is deliberately skewed toward genre 0 so a popularity baseline is "tempting" but wrong for others
        List<Interaction> data = new ArrayList<>();
        Map<String, Integer> userGenre = new HashMap<>();
        for (int u = 0; u < 36; u++) {
            int g = u < 18 ? 0 : (u < 28 ? 1 : 2);
            String uid = "u" + u;
            userGenre.put(uid, g);
            Set<String> chosen = new HashSet<>();
            while (chosen.size() < 8) {
                int gg = rnd.nextDouble() < 0.15 ? rnd.nextInt(3) : g;
                chosen.add(byGenre.get(gg).get(rnd.nextInt(12)));
            }
            for (String tid : chosen) {
                data.add(like(uid, tid));
            }
        }
        Recommender reco = new Recommender(cat, data);
        Map<String, Set<String>> seen = new HashMap<>();
        for (Interaction i : data) {
            seen.computeIfAbsent(i.userId(), k -> new HashSet<>()).add(i.trackId());
        }
        List<Recommendation> popular = reco.recommend("nobody", 100);       // cold-start list = global popularity

        double pHybrid = 0;
        double pPop = 0;
        double pRandom = 0;
        for (String uid : userGenre.keySet()) {
            int g = userGenre.get(uid);
            pHybrid += precision(reco.recommend(uid, 5), genre, g);
            List<Recommendation> pop = new ArrayList<>();
            for (Recommendation r : popular) {
                if (!seen.get(uid).contains(r.trackId())) {
                    pop.add(r);
                }
            }
            pPop += precision(pop.subList(0, 5), genre, g);
            List<String> unseen = new ArrayList<>(cat.keySet());
            unseen.removeAll(seen.get(uid));
            pRandom += unseen.stream().filter(x -> genre.get(x) == g).count() / (double) unseen.size();
        }
        int n = userGenre.size();
        pHybrid /= n;
        pPop /= n;
        pRandom /= n;
        System.out.printf("Precision@5 hybrid=%.3f popularity=%.3f random=%.3f%n", pHybrid, pPop, pRandom);
        assertTrue(pHybrid >= 0.80, "hybrid P@5 " + pHybrid);
        assertTrue(pHybrid > pPop + 0.15, "hybrid should clearly beat popularity: " + pHybrid + " vs " + pPop);
        assertTrue(pHybrid > pRandom + 0.3, "hybrid should clearly beat random: " + pHybrid + " vs " + pRandom);
    }

    private static double precision(List<Recommendation> recs, Map<String, Integer> genre, int g) {
        long hit = recs.stream().filter(r -> genre.get(r.trackId()) == g).count();
        return (double) hit / 5.0;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
