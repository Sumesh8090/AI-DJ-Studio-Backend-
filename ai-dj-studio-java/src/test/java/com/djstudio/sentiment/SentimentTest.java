package com.djstudio.sentiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SentimentTest {

    final SentimentClassifier c = new SentimentClassifier();

    // ------------------------------------------------------------ classifier behaviour

    @Test
    void clearlyPositiveAndNegativeMessages() {
        assertEquals(SentimentClassifier.Label.POSITIVE, c.classify("I love this track").label());
        assertEquals(SentimentClassifier.Label.NEGATIVE, c.classify("I hate this track").label());
        assertEquals(SentimentClassifier.Label.NEUTRAL, c.classify("what is the bpm?").label());
    }

    @Test
    void negationFlipsMeaning() {
        assertTrue(c.classify("this is good").score() > 0.6);
        assertTrue(c.classify("this is not good").score() < 0.4);
        assertTrue(c.classify("not bad at all").score() > 0.6);
    }

    @Test
    void intensifiersAndDampenersChangeStrength() {
        double plain = c.classify("good").score();
        assertTrue(c.classify("really good").score() > plain);
        assertTrue(c.classify("kinda good").score() < plain);
    }

    @Test
    void emojiCountAndEmphasisMatter() {
        assertTrue(c.classify("🔥").score() > 0.6);
        assertTrue(c.classify("👎").score() < 0.4);
        assertTrue(c.classify("love it!!!").score() > c.classify("love it").score());
    }

    @Test
    void blankOrNullIsNeutral() {
        assertEquals(0.5, c.classify("   ").score());
        assertEquals(0.5, c.classify(null).score());
    }

    @Test
    void scoreStaysInRange() {
        String spam = "love ".repeat(200) + "🔥".repeat(50) + "!!!";
        double s = c.classify(spam).score();
        assertTrue(s <= 1.0 && s > 0.9);
        double n = c.classify("hate ".repeat(200)).score();
        assertTrue(n >= 0.0 && n < 0.1);
    }

    // ------------------------------------------------------------ accuracy on labelled sets

    record Row(String label, String text) {
    }

    static List<Row> load(String name) throws Exception {
        List<Row> rows = new ArrayList<>();
        try (InputStream in = SentimentTest.class.getResourceAsStream("/sentiment/" + name);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    String[] p = line.split("\t", 2);
                    rows.add(new Row(p[0], p[1]));
                }
            }
        }
        return rows;
    }

    double accuracy(List<Row> rows) {
        int ok = 0;
        for (Row row : rows) {
            String predicted = switch (c.classify(row.text()).label()) {
                case POSITIVE -> "pos";
                case NEGATIVE -> "neg";
                default -> "neu";
            };
            if (predicted.equals(row.label())) {
                ok++;
            }
        }
        return (double) ok / rows.size();
    }

    @Test
    void developmentSetAccuracyMeetsTheTarget() throws Exception {
        List<Row> rows = load("dev.tsv");
        assertEquals(60, rows.size());
        double acc = accuracy(rows);
        System.out.println("sentiment dev accuracy = " + acc);
        assertTrue(acc >= 0.85, "dev accuracy " + acc);
    }

    /** Written before the classifier was run on it and never used for tuning. */
    @Test
    void heldOutSetAccuracyMeetsTheTarget() throws Exception {
        List<Row> rows = load("heldout.tsv");
        assertEquals(60, rows.size());
        double acc = accuracy(rows);
        System.out.println("sentiment held-out accuracy = " + acc);
        assertTrue(acc >= 0.85, "held-out accuracy " + acc);
    }

    // ------------------------------------------------------------ aggregator

    @Test
    void emptyAggregatorIsNeutral() {
        SentimentAggregator a = SentimentAggregator.standard();
        assertEquals(0.5, a.current(1000));
        assertEquals(0.0, a.trend(1000));
    }

    @Test
    void positiveMessagesRaiseTheMoodAndNegativeOnesLowerIt() {
        SentimentAggregator a = SentimentAggregator.standard();
        a.add(1000, 0.9);
        a.add(2000, 0.9);
        double high = a.current(3000);
        assertTrue(high > 0.65);
        a.add(4000, 0.1);
        a.add(5000, 0.1);
        a.add(6000, 0.1);
        a.add(7000, 0.1);
        assertTrue(a.current(8000) < high);
    }

    @Test
    void oneMessageCannotSwingTheMoodToTheExtreme() {
        SentimentAggregator a = SentimentAggregator.standard();
        a.add(1000, 1.0);
        assertTrue(a.current(1000) < 0.8);
    }

    @Test
    void oldObservationsFadeAndEventuallyDropOut() {
        SentimentAggregator a = SentimentAggregator.standard();
        a.add(0, 1.0);
        double soon = a.current(1000);
        double later = a.current(60_000);
        assertTrue(later < soon);
        assertEquals(0.5, a.current(200_000));                    // outside the 120 s window
    }

    @Test
    void trendIsPositiveWhenGettingHappier() {
        SentimentAggregator a = SentimentAggregator.standard();
        for (int i = 0; i < 5; i++) {
            a.add(i * 1000L, 0.1);
        }
        for (int i = 0; i < 5; i++) {
            a.add(40_000 + i * 1000L, 0.95);
        }
        assertTrue(a.trend(46_000) > 0.1);
        SentimentAggregator down = SentimentAggregator.standard();
        for (int i = 0; i < 5; i++) {
            down.add(i * 1000L, 0.95);
        }
        for (int i = 0; i < 5; i++) {
            down.add(40_000 + i * 1000L, 0.1);
        }
        assertTrue(down.trend(46_000) < -0.1);
    }

    @Test
    void scoresAreClamped() {
        SentimentAggregator a = SentimentAggregator.standard();
        a.add(0, 5.0);
        a.add(1, -3.0);
        double v = a.current(2);
        assertTrue(v >= 0.0 && v <= 1.0);
    }
}
