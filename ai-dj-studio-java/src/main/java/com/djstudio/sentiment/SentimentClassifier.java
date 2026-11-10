package com.djstudio.sentiment;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lexicon-based sentiment scorer for short chat messages (no model, no network, fully deterministic).
 *
 * <p>Score is in [0,1]: 0 = very negative, 0.5 = neutral, 1 = very positive. It handles the things that matter in a
 * music chat: emoji and emoticons, negation ("not good"), intensifiers ("so good") and dampeners ("kinda meh"),
 * a few common phrases, and emphasis (!, ALL CAPS). It is deliberately simple and explainable; its measured accuracy
 * on the labelled sets is reported in the tests and README.
 */
public final class SentimentClassifier {

    public enum Label { NEGATIVE, NEUTRAL, POSITIVE }

    public record Result(double score, Label label) {
    }

    public static final double POSITIVE_AT = 0.60;
    public static final double NEGATIVE_AT = 0.40;

    private static final Map<String, Double> WORDS = Map.ofEntries(
            // positive
            Map.entry("love", 2.0), Map.entry("loved", 2.0), Map.entry("loving", 2.0), Map.entry("great", 1.6),
            Map.entry("amazing", 2.0), Map.entry("awesome", 2.0), Map.entry("fire", 1.8), Map.entry("banger", 2.0),
            Map.entry("bangers", 2.0), Map.entry("vibes", 1.3), Map.entry("vibe", 1.1), Map.entry("vibing", 1.5),
            Map.entry("sick", 1.6), Map.entry("dope", 1.6), Map.entry("perfect", 2.0), Map.entry("beautiful", 1.8),
            Map.entry("smooth", 1.3), Map.entry("groovy", 1.4), Map.entry("nice", 1.2), Map.entry("good", 1.2),
            Map.entry("best", 1.8), Map.entry("incredible", 2.0), Map.entry("goosebumps", 1.8), Map.entry("lit", 1.5),
            Map.entry("slaps", 1.7), Map.entry("slap", 1.5), Map.entry("heat", 1.4), Map.entry("wow", 1.3),
            Map.entry("enjoy", 1.4), Map.entry("enjoying", 1.4), Map.entry("enjoyed", 1.4), Map.entry("favorite", 1.8),
            Map.entry("favourite", 1.8), Map.entry("killer", 1.6), Map.entry("fantastic", 2.0), Map.entry("brilliant", 1.8),
            Map.entry("fun", 1.2), Map.entry("happy", 1.4), Map.entry("chill", 0.9), Map.entry("relaxing", 1.1),
            Map.entry("energy", 0.8), Map.entry("hype", 1.4), Map.entry("hyped", 1.5), Map.entry("thanks", 0.8),
            Map.entry("thank", 0.8), Map.entry("yes", 0.8), Map.entry("yess", 1.2), Map.entry("yesss", 1.4),
            Map.entry("cool", 1.0), Map.entry("fresh", 1.2), Map.entry("clean", 0.9), Map.entry("tight", 1.0),
            Map.entry("gorgeous", 1.8), Map.entry("masterpiece", 2.0), Map.entry("classic", 1.4), Map.entry("welcome", 0.6),
            Map.entry("dancing", 1.3), Map.entry("dance", 1.0), Map.entry("bop", 1.5), Map.entry("catchy", 1.3),
            // negative
            Map.entry("hate", -2.0), Map.entry("hated", -2.0), Map.entry("boring", -1.7), Map.entry("bored", -1.6),
            Map.entry("bad", -1.4), Map.entry("awful", -2.0), Map.entry("terrible", -2.0), Map.entry("worst", -2.0),
            Map.entry("trash", -1.9), Map.entry("garbage", -1.9), Map.entry("skip", -1.6), Map.entry("skipping", -1.5),
            Map.entry("meh", -1.1), Map.entry("annoying", -1.7), Map.entry("cringe", -1.6), Map.entry("mid", -1.0),
            Map.entry("lame", -1.5), Map.entry("dull", -1.4), Map.entry("sucks", -1.8), Map.entry("ugh", -1.2),
            Map.entry("clash", -1.2), Map.entry("clashing", -1.4), Map.entry("messy", -1.3), Map.entry("painful", -1.7),
            Map.entry("tired", -0.9), Map.entry("repetitive", -1.3), Map.entry("flat", -1.0), Map.entry("harsh", -1.2),
            Map.entry("disappointing", -1.6), Map.entry("disappointed", -1.6), Map.entry("overrated", -1.3),
            Map.entry("noise", -1.3), Map.entry("noisy", -1.3), Map.entry("ruined", -1.7), Map.entry("ruins", -1.6),
            Map.entry("off", -0.6), Map.entry("wrong", -1.1), Map.entry("sleepy", -0.8), Map.entry("snooze", -1.4),
            Map.entry("yawn", -1.3), Map.entry("nope", -1.2), Map.entry("no", -0.3), Map.entry("worse", -1.6),
            Map.entry("dead", -0.9), Map.entry("dreadful", -1.9), Map.entry("irritating", -1.6), Map.entry("shame", -1.0));

    private static final Map<String, Double> EMOJI = Map.ofEntries(
            Map.entry("🔥", 1.8), Map.entry("❤", 1.8), Map.entry("😍", 2.0), Map.entry("👏", 1.4), Map.entry("🙌", 1.5),
            Map.entry("🎉", 1.4), Map.entry("💃", 1.4), Map.entry("🕺", 1.4), Map.entry("😊", 1.3), Map.entry("😀", 1.3),
            Map.entry("😎", 1.2), Map.entry("🤩", 1.8), Map.entry("💯", 1.6), Map.entry("🎶", 0.8), Map.entry("👍", 1.2),
            Map.entry("🥳", 1.5), Map.entry("😂", 0.6), Map.entry("👎", -1.6), Map.entry("😴", -1.3), Map.entry("😡", -1.8),
            Map.entry("🤮", -2.0), Map.entry("😒", -1.4), Map.entry("💩", -1.8), Map.entry("😞", -1.4), Map.entry("😠", -1.7),
            Map.entry("🙄", -1.3), Map.entry("😕", -1.0), Map.entry("🤢", -1.8));

    private static final Map<String, Double> EMOTICONS = Map.of(
            ":)", 1.2, ":D", 1.5, "<3", 1.8, ":(", -1.3, ":/", -0.9, ":-)", 1.2, ":-(", -1.3, "xd", 0.8);

    private static final Map<String, Double> PHRASES = Map.of(
            "turn it up", 1.6, "let's go", 1.5, "lets go", 1.5, "so good", 0.4, "play this again", 1.4,
            "no thanks", -1.3, "not for me", -1.3, "turn it down", -1.2, "next one", -0.8, "change it", -1.0);

    private static final Set<String> NEGATORS = Set.of("not", "never", "no", "dont", "don't", "isnt", "isn't",
            "wasnt", "wasn't", "cant", "can't", "cannot", "without", "hardly", "aint", "ain't", "doesnt", "doesn't");
    private static final Map<String, Double> BOOSTERS = Map.ofEntries(
            Map.entry("so", 1.4), Map.entry("very", 1.4), Map.entry("really", 1.4), Map.entry("super", 1.5),
            Map.entry("absolutely", 1.6), Map.entry("totally", 1.4), Map.entry("insanely", 1.7), Map.entry("mad", 1.4),
            Map.entry("extremely", 1.6), Map.entry("too", 1.3), Map.entry("kinda", 0.6), Map.entry("bit", 0.6),
            Map.entry("slightly", 0.5), Map.entry("little", 0.6), Map.entry("somewhat", 0.6), Map.entry("sorta", 0.6));

    public Result classify(String message) {
        if (message == null || message.isBlank()) {
            return new Result(0.5, Label.NEUTRAL);
        }
        String text = message.strip();
        String lower = text.toLowerCase(Locale.ROOT);
        double raw = 0.0;

        for (var e : PHRASES.entrySet()) {
            if (lower.contains(e.getKey())) {
                raw += e.getValue();
            }
        }
        for (var e : EMOJI.entrySet()) {
            raw += e.getValue() * count(text, e.getKey());
        }
        for (var e : EMOTICONS.entrySet()) {
            raw += e.getValue() * count(lower, e.getKey());
        }

        String[] tokens = lower.replaceAll("[^\\p{L}\\p{N}'’]+", " ").replace('’', '\'').trim().split("\\s+");
        String[] original = text.replaceAll("[^\\p{L}\\p{N}'’]+", " ").trim().split("\\s+");
        int negationLeft = 0;
        double boost = 1.0;
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.isEmpty()) {
                continue;
            }
            if (NEGATORS.contains(t)) {
                negationLeft = 3;
                if (!WORDS.containsKey(t)) {
                    continue;
                }
            }
            Double booster = BOOSTERS.get(t);
            if (booster != null && !WORDS.containsKey(t)) {
                boost = booster;
                continue;
            }
            Double v = WORDS.get(collapse(t));
            if (v == null) {
                if (negationLeft > 0) {
                    negationLeft--;
                }
                continue;
            }
            double value = v * boost;
            if (original.length == tokens.length && original[i].length() > 2 && original[i].equals(original[i].toUpperCase(Locale.ROOT))
                    && !original[i].equals(original[i].toLowerCase(Locale.ROOT))) {
                value *= 1.3; // SHOUTING
            }
            if (negationLeft > 0 && !NEGATORS.contains(t)) {
                value = -0.8 * value;
                negationLeft--;
            }
            raw += value;
            boost = 1.0;
        }

        long bangs = text.chars().filter(c -> c == '!').count();
        if (bangs > 0 && raw != 0) {
            raw *= 1.0 + Math.min(bangs, 3) * 0.12;
        }
        double score = 0.5 + 0.5 * Math.tanh(raw / 2.0);
        score = Math.round(score * 1000.0) / 1000.0;
        return new Result(score, label(score));
    }

    public static Label label(double score) {
        return score >= POSITIVE_AT ? Label.POSITIVE : score <= NEGATIVE_AT ? Label.NEGATIVE : Label.NEUTRAL;
    }

    /** "yesssss" -> "yesss" so stretched words still match; no effect on normal words. */
    private static String collapse(String t) {
        return t.replaceAll("(.)\\1{3,}", "$1$1$1");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
