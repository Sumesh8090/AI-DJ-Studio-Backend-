package com.djstudio.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 24-key Camelot wheel notation and harmonic-compatibility rules (PRD 6.2).
 *
 * <p>Compatible relationships for an immediate segue:
 * <ul>
 *   <li>identical - 8A to 8A</li>
 *   <li>adjacent - same letter, number +/- 1 (8A to 7A / 9A). The wheel is circular, so 12 and 1 are neighbours.</li>
 *   <li>relative - same number, opposite letter (8A to 8B, relative major/minor)</li>
 * </ul>
 * Everything else is a clash. Pure Java: no Spring or database dependencies.
 */
public final class CamelotWheel {

    private static final Pattern KEY = Pattern.compile("^(1[0-2]|[1-9])([AB])$");

    public static final Set<String> VALID_KEYS;

    static {
        Set<String> keys = new LinkedHashSet<>();
        for (char letter : new char[] {'A', 'B'}) {
            for (int n = 1; n <= 12; n++) {
                keys.add(n + String.valueOf(letter));
            }
        }
        VALID_KEYS = Collections.unmodifiableSet(keys);
    }

    /** How a candidate key relates to the current key, with its "closeness" for ranking. */
    public enum Relation {
        IDENTICAL("identical", 1.0),
        ADJACENT("adjacent", 0.8),
        RELATIVE("relative", 0.7);

        private final String wire;
        private final double closeness;

        Relation(String wire, double closeness) {
            this.wire = wire;
            this.closeness = closeness;
        }

        public String wire() {
            return wire;
        }

        public double closeness() {
            return closeness;
        }
    }

    private CamelotWheel() {
    }

    /** Returns the canonical key (trimmed, upper-case) or throws IllegalArgumentException with a clear message. */
    public static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Camelot key must be a string like '8A' (got null).");
        }
        String key = raw.strip().toUpperCase(Locale.ROOT);
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "Invalid Camelot key '" + raw + "': expected one of 1A-12A or 1B-12B (24 valid keys).");
        }
        return key;
    }

    public static boolean isValid(String raw) {
        try {
            normalize(raw);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Returns the relation, or empty if the two keys clash. */
    public static Optional<Relation> relation(String current, String candidate) {
        Matcher a = KEY.matcher(normalize(current));
        Matcher b = KEY.matcher(normalize(candidate));
        if (!a.matches() || !b.matches()) {
            throw new IllegalStateException("unreachable: keys were normalised");
        }
        int curNum = Integer.parseInt(a.group(1));
        int candNum = Integer.parseInt(b.group(1));
        String curLetter = a.group(2);
        String candLetter = b.group(2);

        if (curNum == candNum && curLetter.equals(candLetter)) {
            return Optional.of(Relation.IDENTICAL);
        }
        if (curLetter.equals(candLetter)) {
            int diff = Math.floorMod(curNum - candNum, 12);
            if (diff == 1 || diff == 11) {
                return Optional.of(Relation.ADJACENT);
            }
        }
        if (curNum == candNum) { // letters differ here
            return Optional.of(Relation.RELATIVE);
        }
        return Optional.empty();
    }

    public static boolean isCompatible(String current, String candidate) {
        return relation(current, candidate).isPresent();
    }
}
