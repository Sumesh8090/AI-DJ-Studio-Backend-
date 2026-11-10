package com.djstudio.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiTest {

    static final MetaEntry SAMPLE = new MetaEntry("1", "sample_credit",
            "The vocal chop is sampled from a 1978 soul record", 42);
    static final MetaEntry TRIVIA = new MetaEntry("2", "trivia", "Recorded in one overnight session", null);
    static final MetaEntry TERM = new MetaEntry("3", "glossary", "Sidechain means the pads duck under the kick", null);

    static final TrackMeta FROM = new TrackMeta("t_1", "Night Drive", 124, "8A", 0.65, List.of());
    static final TrackMeta TO = new TrackMeta("t_2", "Glass Harbor", 128, "9A", 0.80, List.of(TERM, TRIVIA, SAMPLE));

    final TransitionWriter writer = new TransitionWriter();
    final Retriever retriever = new Retriever();

    @Test
    void scriptStatesOnlyFactsFromMetadata() {
        TransitionWriter.Script s = writer.write(FROM, TO, Map.of("bpmRelation", "direct", "camelotRelation", "adjacent"),
                TransitionWriter.Verbosity.NORMAL);
        assertTrue(s.text().contains("124 BPM to 128 BPM"));
        assertTrue(s.text().contains("8A to 9A"));
        assertTrue(s.text().contains("neighbouring Camelot position"));
        assertTrue(s.text().contains("Energy lifts."));
    }

    @Test
    void verbosityControlsHowMuchLoreIsQuotedAndAlwaysCitesIt() {
        Map<String, Object> reason = Map.of("bpmRelation", "direct", "camelotRelation", "adjacent");
        assertEquals(List.of(), writer.write(FROM, TO, reason, TransitionWriter.Verbosity.QUIET).sourceEvents());
        TransitionWriter.Script normal = writer.write(FROM, TO, reason, TransitionWriter.Verbosity.NORMAL);
        assertEquals(List.of("evt_1"), normal.sourceEvents());              // sample credit has top priority
        assertTrue(normal.text().contains("sampled from a 1978 soul record."));
        TransitionWriter.Script chatty = writer.write(FROM, TO, reason, TransitionWriter.Verbosity.CHATTY);
        assertEquals(List.of("evt_1", "evt_2"), chatty.sourceEvents());
    }

    @Test
    void everyQuotedSentenceHasACitation() {
        TransitionWriter.Script s = writer.write(FROM, TO, Map.of(), TransitionWriter.Verbosity.CHATTY);
        for (String c : s.sourceEvents()) {
            assertTrue(c.startsWith("evt_"));
        }
        assertFalse(s.text().contains("Sidechain"));                        // not selected, so never mentioned
    }

    @Test
    void halfTimeAndDoubleTimeAreDescribed() {
        TrackMeta slow = new TrackMeta("t_4", "Low Tide", 62, "7A", 0.4, List.of());
        String t = writer.write(FROM, slow, Map.of("bpmRelation", "half_time", "camelotRelation", "adjacent"),
                TransitionWriter.Verbosity.QUIET).text();
        assertTrue(t.contains("half-time"));
        assertTrue(t.contains("124 BPM to 62 BPM"));
    }

    @Test
    void scriptIsDeterministic() {
        Map<String, Object> r = Map.of("bpmRelation", "direct", "camelotRelation", "identical");
        assertEquals(writer.write(FROM, TO, r, TransitionWriter.Verbosity.CHATTY),
                writer.write(FROM, TO, r, TransitionWriter.Verbosity.CHATTY));
    }

    @Test
    void verbosityParsingFallsBackToNormal() {
        assertEquals(TransitionWriter.Verbosity.QUIET, TransitionWriter.Verbosity.parse(" quiet "));
        assertEquals(TransitionWriter.Verbosity.NORMAL, TransitionWriter.Verbosity.parse("loud"));
        assertEquals(TransitionWriter.Verbosity.NORMAL, TransitionWriter.Verbosity.parse(null));
    }

    // ------------------------------------------------------------ retrieval

    @Test
    void answersFromMatchingEntryAndCitesIt() {
        Retriever.Answer a = retriever.answer("Was anything sampled in this track?", TO, List.of(FROM, TO));
        assertTrue(a.grounded());
        assertEquals(List.of("evt_1"), a.citations());
        assertTrue(a.text().contains("1978 soul record"));
    }

    @Test
    void stemmingMatchesWordForms() {
        assertEquals(Retriever.stem("sampling"), Retriever.stem("sample"));
        assertEquals(Retriever.stem("sampled"), Retriever.stem("samples"));
    }

    @Test
    void declinesWhenNothingRelevantExists() {
        Retriever.Answer a = retriever.answer("Who produced the drum machine?", TO, List.of(FROM, TO));
        assertFalse(a.grounded());
        assertEquals(Retriever.DECLINE, a.text());
        assertTrue(a.citations().isEmpty());
    }

    @Test
    void neverInventsFactsForATrackWithNoNotes() {
        Retriever.Answer a = retriever.answer("Tell me the sample credits", FROM, List.of(FROM, TO));
        assertFalse(a.grounded());
    }

    @Test
    void acousticQuestionsAreAnsweredFromFeatures() {
        Retriever.Answer tempo = retriever.answer("What's the BPM?", TO, List.of(TO));
        assertTrue(tempo.text().contains("128 BPM"));
        assertEquals(List.of("track:t_2:bpm"), tempo.citations());
        Retriever.Answer key = retriever.answer("which key is it in", TO, List.of(TO));
        assertTrue(key.text().contains("9A"));
    }

    @Test
    void aTrackNamedInTheQuestionIsTheSubject() {
        Retriever.Answer a = retriever.answer("What is the tempo of Night Drive?", TO, List.of(FROM, TO));
        assertTrue(a.text().contains("Night Drive runs at 124 BPM"));
    }

    @Test
    void blankQuestionsAreDeclined() {
        assertFalse(retriever.answer("   ", TO, List.of(TO)).grounded());
        assertFalse(retriever.answer(null, TO, List.of(TO)).grounded());
    }

    @Test
    void glossaryQuestionsFindTheTerm() {
        Retriever.Answer a = retriever.answer("What does sidechain mean?", TO, List.of(TO));
        assertTrue(a.grounded());
        assertEquals(List.of("evt_3"), a.citations());
    }
}
