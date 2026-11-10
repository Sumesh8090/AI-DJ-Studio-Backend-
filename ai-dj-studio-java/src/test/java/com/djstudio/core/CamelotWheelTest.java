package com.djstudio.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djstudio.core.CamelotWheel.Relation;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CamelotWheelTest {

    @Test
    void thereAreExactly24ValidKeys() {
        assertEquals(24, CamelotWheel.VALID_KEYS.size());
        assertTrue(CamelotWheel.VALID_KEYS.containsAll(List.of("1A", "12B", "8A", "8B")));
    }

    @Test
    void invalidKeysAreRejectedWithAClearMessage() {
        for (String raw : List.of("0A", "13A", "8C", "A8", "", "08A", "8", "AA", "8AB", " ")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CamelotWheel.normalize(raw));
            assertTrue(e.getMessage().contains("Camelot"), raw);
        }
        assertThrows(IllegalArgumentException.class, () -> CamelotWheel.normalize(null));
    }

    @Test
    void normalisationIsCaseAndSpaceInsensitive() {
        assertEquals("8A", CamelotWheel.normalize(" 8a "));
        assertTrue(CamelotWheel.isValid("12b"));
        assertFalse(CamelotWheel.isValid("13b"));
    }

    private static void expect(String current, String candidate, Relation expected) {
        Optional<Relation> actual = CamelotWheel.relation(current, candidate);
        assertEquals(Optional.ofNullable(expected), actual, current + " -> " + candidate);
    }

    @Test
    void keyRelations() {
        expect("8A", "8A", Relation.IDENTICAL);
        expect("8A", "7A", Relation.ADJACENT);
        expect("8A", "9A", Relation.ADJACENT);
        expect("8A", "8B", Relation.RELATIVE);
        expect("12A", "1A", Relation.ADJACENT); // the wheel wraps
        expect("1B", "12B", Relation.ADJACENT);
        expect("8A", "9B", null);               // a diagonal move is a clash
        expect("8A", "10A", null);              // two steps is a clash
        expect("8A", "3A", null);
        expect("8B", "7A", null);
    }

    @Test
    void relationIsSymmetricForEveryPair() {
        for (String a : CamelotWheel.VALID_KEYS) {
            for (String b : CamelotWheel.VALID_KEYS) {
                assertEquals(CamelotWheel.relation(a, b), CamelotWheel.relation(b, a), a + "/" + b);
            }
        }
    }

    @Test
    void eachKeyHasExactlyFourCompatibleKeysIncludingItself() {
        for (String key : CamelotWheel.VALID_KEYS) {
            long compatible = CamelotWheel.VALID_KEYS.stream().filter(k -> CamelotWheel.isCompatible(key, k)).count();
            assertEquals(4L, compatible, key); // self, two neighbours, relative
        }
    }
}
