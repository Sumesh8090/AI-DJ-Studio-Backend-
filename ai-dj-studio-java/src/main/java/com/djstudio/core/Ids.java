package com.djstudio.core;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/** Opaque public identifiers and invite codes. */
public final class Ids {

    /** No I, L, O, 0, 1: easy to read aloud and type from a screen. */
    private static final String INVITE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final Pattern INVITE = Pattern.compile("^SP-[A-HJKMNP-Z2-9]{4}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Ids() {
    }

    /** e.g. newId("u") returns "u_3fa91c0b2d4e". */
    public static String newId(String prefix) {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(prefix).append('_');
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }

    public static String newInviteCode() {
        StringBuilder sb = new StringBuilder("SP-");
        for (int i = 0; i < 4; i++) {
            sb.append(INVITE_ALPHABET.charAt(RANDOM.nextInt(INVITE_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Returns the canonical code or throws IllegalArgumentException. */
    public static String normalizeInviteCode(String raw) {
        String code = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!INVITE.matcher(code).matches()) {
            throw new IllegalArgumentException("Invite code must look like 'SP-9X2L'.");
        }
        return code;
    }
}
