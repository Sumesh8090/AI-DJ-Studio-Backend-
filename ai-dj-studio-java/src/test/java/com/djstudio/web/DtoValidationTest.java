package com.djstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djstudio.web.dto.AuthDtos.RegisterRequest;
import com.djstudio.web.dto.LoungeDtos.LoungeCreateRequest;
import com.djstudio.web.dto.TrackDtos.AcousticRequest;
import com.djstudio.web.dto.TrackDtos.TimelineEventRequest;
import com.djstudio.web.dto.TrackDtos.TrackCreateRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The acoustic-upload validation contract (PRD 6.3 acceptance), tested without a database. */
class DtoValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static AcousticRequest acoustic(Double bpm, String key, Double energy, Double valence) {
        return new AcousticRequest(bpm, key, energy, valence);
    }

    private <T> String messages(T value) {
        Set<ConstraintViolation<T>> v = validator.validate(value);
        return v.stream().map(x -> x.getPropertyPath() + ": " + x.getMessage()).sorted().collect(Collectors.joining(" | "));
    }

    @Test
    void validProfileIsAccepted() {
        assertEquals("", messages(acoustic(124.0, "8A", 0.78, 0.65)));
        assertEquals("", messages(acoustic(300.0, "12B", 0.0, 1.0))); // boundaries are inclusive
        assertEquals("", messages(acoustic(124.0, " 8a ", 0.5, 0.5))); // key is normalised later, so it is accepted
    }

    @Test
    void badBpmIsRejectedWithAClearMessage() {
        for (double bpm : new double[] {0, -5, -0.0001}) {
            assertTrue(messages(acoustic(bpm, "8A", 0.5, 0.5)).contains("BPM must be a positive number"), "bpm=" + bpm);
        }
        assertTrue(messages(acoustic(301.0, "8A", 0.5, 0.5)).contains("BPM must be at most 300"));
        assertTrue(messages(acoustic(null, "8A", 0.5, 0.5)).contains("BPM is required"));
    }

    @Test
    void badCamelotKeysAreRejected() {
        for (String key : List.of("13A", "0B", "8C", "", "H", "08A", "A8")) {
            assertTrue(messages(acoustic(120.0, key, 0.5, 0.5)).contains("Camelot"), "key=" + key);
        }
        assertTrue(messages(acoustic(120.0, null, 0.5, 0.5)).contains("Camelot key is required"));
    }

    @Test
    void energyAndValenceMustBeInTheUnitInterval() {
        for (double v : new double[] {-0.01, 1.01, 5}) {
            assertTrue(messages(acoustic(120.0, "8A", v, 0.5)).contains("Energy must be between 0.0 and 1.0"), "e=" + v);
            assertTrue(messages(acoustic(120.0, "8A", 0.5, v)).contains("Valence must be between 0.0 and 1.0"), "v=" + v);
        }
    }

    private static TrackCreateRequest track(String id, String url, List<TimelineEventRequest> timeline) {
        return new TrackCreateRequest(id, "Title", "Artist", 200, url, acoustic(120.0, "8A", 0.5, 0.5), timeline);
    }

    @Test
    void trackIdAndAudioUrlAreConstrained() {
        assertEquals("", messages(track("t_9001", "/media/a.mp3", null)));
        assertEquals("", messages(track(null, "https://cdn.example.com/a.mp3", List.of())));
        assertTrue(messages(track("9001; DROP TABLE", null, null)).contains("id must look like"));
        for (String url : List.of("javascript:alert(1)", "ftp://x/y.mp3", "no scheme.mp3", "http://x/<s>")) {
            assertTrue(messages(track(null, url, null)).contains("audioAssetUrl"), url);
        }
    }

    @Test
    void timelineEntriesAreValidated() {
        assertTrue(messages(track(null, null, List.of(new TimelineEventRequest(-1, "trivia", "x")))).contains("ts must be >= 0"));
        assertTrue(messages(track(null, null, List.of(new TimelineEventRequest(1, "gossip", "x")))).contains("type must be"));
        assertTrue(messages(track(null, null, List.of(new TimelineEventRequest(1, "trivia", "x".repeat(501))))).contains("at most 500"));
        assertEquals("", messages(track(null, null, List.of(new TimelineEventRequest(5, "trivia", "A fact.")))));
    }

    @Test
    void registrationBlocksPrivilegeEscalationAndWeakInput() {
        assertEquals("", messages(new RegisterRequest("me@example.com", "longenough", "Me", null)));
        assertEquals("", messages(new RegisterRequest("me@example.com", "longenough", "Me", "host")));
        for (String role : List.of("admin", "co_dj", "root")) {
            assertTrue(messages(new RegisterRequest("me@example.com", "longenough", "Me", role)).contains("Role must be"), role);
        }
        assertTrue(messages(new RegisterRequest("not-an-email", "longenough", "Me", null)).contains("valid email"));
        assertTrue(messages(new RegisterRequest("me@example.com", "short", "Me", null)).contains("Password must be"));
    }

    @Test
    void loungeCreateConstraintsMatchThePrd() {
        assertEquals("", messages(new LoungeCreateRequest("t_2001", "High-Energy EDM", null, null, null, null)));
        assertTrue(messages(new LoungeCreateRequest("t_2001", "x", 1, null, null, null)).contains("maxParticipants"));
        assertTrue(messages(new LoungeCreateRequest("t_2001", "x", null, 50.0, null, null)).contains("bpmTolerancePct"));
        assertTrue(messages(new LoungeCreateRequest("t_2001", "x", null, null, null, "loud")).contains("aiVerbosity"));
        assertTrue(messages(new LoungeCreateRequest("t_2001", "  ", null, null, null, null)).contains("vibeProfile"));
    }
}
