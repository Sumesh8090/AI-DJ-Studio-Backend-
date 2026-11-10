package com.djstudio.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PermissionSanitizerIdsTest {

    // ---------------------------------------------------------------- RBAC
    @Test
    void roleHierarchyMatrix() {
        Map<Permission, Set<String>> expected = Map.of(
                Permission.JOIN_LOUNGE, Set.of("listener", "co_dj", "host", "admin"),
                Permission.BROWSE_TRACKS, Set.of("listener", "co_dj", "host", "admin"),
                Permission.CREATE_LOUNGE, Set.of("host", "admin"),
                Permission.MANAGE_LOUNGE, Set.of("host", "admin"),
                Permission.OVERRIDE_QUEUE, Set.of("co_dj", "host", "admin"),
                Permission.RESOLVE_BATTLE, Set.of("co_dj", "host", "admin"),
                Permission.MANAGE_TRACKS, Set.of("admin"),
                Permission.MANAGE_USERS, Set.of("admin"));
        assertEquals(Permission.values().length, expected.size(), "every permission has an expectation");
        for (Permission p : Permission.values()) {
            for (Role r : Role.values()) {
                assertEquals(expected.get(p).contains(r.value()), p.allows(r.value()), p + " / " + r);
            }
        }
    }

    @Test
    void unknownRolesAreDenied() {
        for (Permission p : Permission.values()) {
            assertFalse(p.allows("superuser"));
            assertFalse(p.allows(""));
            assertFalse(p.allows(null));
            assertFalse(p.allows("ADMIN")); // wire values are lower-case
        }
    }

    @Test
    void roleRoundTrip() {
        for (Role r : Role.values()) {
            assertEquals(r, Role.fromValue(r.value()).orElseThrow());
        }
        assertTrue(Role.fromValue("nope").isEmpty());
    }

    // ----------------------------------------------------------- sanitiser
    @Test
    void htmlIsEscapedAndControlCharactersRemoved() {
        String out = Sanitizer.sanitize("<script>alert(1)</script>\u0000hi", 100, "text");
        assertFalse(out.contains("<"));
        assertFalse(out.contains("\u0000"));
        assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;hi", out);
        assertEquals("a &amp; b &quot;c&quot; &#x27;d&#x27;", Sanitizer.sanitize("a & b \"c\" 'd'", 100, "text"));
    }

    @Test
    void lengthAndEmptinessAreEnforced() {
        IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class,
                () -> Sanitizer.sanitize("abcdefg", 5, "name"));
        assertTrue(tooLong.getMessage().contains("at most 5"));
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> Sanitizer.sanitize("   \u0001 ", 5, "name"));
        assertTrue(empty.getMessage().contains("empty"));
        assertThrows(IllegalArgumentException.class, () -> Sanitizer.sanitize(null, 5, "name"));
    }

    @Test
    void lengthIsCheckedOnRawTextNotAfterEscaping() {
        assertEquals("&lt;&lt;&lt;", Sanitizer.sanitize("<<<", 3, "t"));
    }

    // ----------------------------------------------------------------- ids
    @Test
    void inviteCodesAreWellFormedAndValidated() {
        for (int i = 0; i < 200; i++) {
            String code = Ids.newInviteCode();
            assertEquals(code, Ids.normalizeInviteCode(code.toLowerCase()));
        }
        for (String bad : List.of("", "SP-12", "XX-ABCD", "SP-0O1I", "SP-ABCDE")) {
            assertThrows(IllegalArgumentException.class, () -> Ids.normalizeInviteCode(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> Ids.normalizeInviteCode(null));
    }

    @Test
    void newIdsHavePrefixAndAreUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(Ids.newId("u"));
        }
        assertEquals(500, seen.size());
        assertTrue(Ids.newId("lg").startsWith("lg_"));
    }
}
