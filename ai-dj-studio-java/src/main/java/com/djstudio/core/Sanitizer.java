package com.djstudio.core;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Server-side sanitisation for free text (chat, AI questions, track metadata). PRD 10.1.
 *
 * <p>Strategy: drop control characters, trim, enforce a length cap on the raw text, then HTML-escape so a stored
 * value can never inject markup when rendered. Clients must render the result as text.
 */
public final class Sanitizer {

    /** Control characters except tab (\t) and newline (\n). */
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    private Sanitizer() {
    }

    public static String sanitize(String value, int maxLen, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must be a string.");
        }
        String cleaned = CONTROL.matcher(Normalizer.normalize(value, Normalizer.Form.NFC)).replaceAll("").strip();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty.");
        }
        if (cleaned.length() > maxLen) {
            throw new IllegalArgumentException(
                    field + " must be at most " + maxLen + " characters (got " + cleaned.length() + ").");
        }
        return escapeHtml(cleaned);
    }

    static String escapeHtml(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#x27;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
