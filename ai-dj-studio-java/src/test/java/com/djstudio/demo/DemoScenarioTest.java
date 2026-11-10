package com.djstudio.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djstudio.ai.MetaEntry;
import com.djstudio.ai.Retriever;
import com.djstudio.ai.TrackMeta;
import com.djstudio.ai.TransitionWriter;
import com.djstudio.core.Permission;
import com.djstudio.core.Role;
import com.djstudio.core.SegueMatcher;
import com.djstudio.core.TrackFeatures;
import com.djstudio.room.Effect;
import com.djstudio.room.OutEvent;
import com.djstudio.room.Result;
import com.djstudio.room.Room;
import com.djstudio.room.RoomException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * A scripted end-to-end session over the real room engine, matcher, script writer, Q&A and sentiment code, using the
 * seed tracks, printed as a transcript. It is the "synthetic stream" demo (PRD Track B): every number below is
 * computed by the production classes, nothing is hand-written. Run it to regenerate docs/evidence/scripted-demo-output.txt.
 */
class DemoScenarioTest {

    final Map<String, TrackMeta> meta = new LinkedHashMap<>();
    final Map<String, Integer> duration = new LinkedHashMap<>();
    final Map<String, Double> valence = new LinkedHashMap<>();

    void add(TrackMeta m, double v, int seconds) {
        meta.put(m.trackId(), m);
        valence.put(m.trackId(), v);
        duration.put(m.trackId(), seconds);
    }

    DemoScenarioTest() {
        add(new TrackMeta("t_2001", "Night Drive", 124, "8A", 0.78, List.of(
            new MetaEntry("t_2001_0", "sample_credit", "Vocal chop sampled from a 1981 soul record.", 12),
            new MetaEntry("t_2001_1", "trivia", "Produced during an all-night session in Berlin.", 90),
            new MetaEntry("t_2001_2", "glossary", "The build-up uses a rising filter sweep over a four-bar snare roll.", 150))), 0.65, 232);
        add(new TrackMeta("t_2002", "Glass Harbor", 128, "9A", 0.84, List.of(
            new MetaEntry("t_2002_0", "sample_credit", "Percussion loop recorded from a harbour crane clanging at dawn.", 20),
            new MetaEntry("t_2002_1", "trivia", "Written as a remix brief that ended up replacing the original.", 105))), 0.72, 214);
        add(new TrackMeta("t_2003", "Paper Lanterns", 120, "8A", 0.7, List.of(
            new MetaEntry("t_2003_0", "trivia", "Features a field recording of a night market in Taipei.", 30),
            new MetaEntry("t_2003_1", "glossary", "A breakdown is the stripped-back section where the drums drop out.", 140))), 0.6, 247);
        add(new TrackMeta("t_2004", "Low Tide Loops", 62, "7A", 0.35, List.of(
            new MetaEntry("t_2004_0", "trivia", "Half-time companion to Night Drive; the same chord loop slowed to 62 BPM.", 45))), 0.5, 301);
        add(new TrackMeta("t_2005", "Static Bloom", 140, "3B", 0.93, List.of(
            new MetaEntry("t_2005_0", "trivia", "Intentionally clashes with most mid-tempo tracks; used as a negative test case.", 8))), 0.41, 198);
        add(new TrackMeta("t_2006", "Midnight Relay", 126, "3A", 0.74, List.of(
            new MetaEntry("t_2006_0", "trivia", "Tempo-compatible with Night Drive but in a clashing key; a key-clash test case.", 60))), 0.55, 221);
        add(new TrackMeta("t_2007", "Sunday Static", 90, "8B", 0.45, List.of(
            new MetaEntry("t_2007_0", "trivia", "Harmonically relative to Night Drive but far too slow; a BPM-clash test case.", 25))), 0.62, 265);
    }

    // two connected "browser tabs": everything the room broadcasts, as each client receives it
    final Map<String, List<OutEvent>> inbox = new LinkedHashMap<>();
    Room room;

    Result exec(Function<Room, Result> op) {
        Result r = op.apply(room);
        for (OutEvent e : r.events()) {
            for (var c : inbox.entrySet()) {
                if (e.isBroadcast() || c.getKey().equals(e.toUserId())) {
                    c.getValue().add(e);
                }
            }
        }
        return r;
    }

    TrackFeatures features(String id) {
        TrackMeta m = meta.get(id);
        return new TrackFeatures(id, m.bpm(), m.camelotKey(), m.energy(), valence.get(id), m.title());
    }

    Room.TrackRef ref(String id) {
        return new Room.TrackRef(id, meta.get(id).title(), duration.get(id));
    }

    static void say(String s) {
        System.out.println(s);
    }

    String queueLine() {
        StringBuilder sb = new StringBuilder();
        for (var s : room.queueSlots()) {
            sb.append(meta.get(s.trackId()).title()).append(" (").append(s.voteCount()).append(" votes)  ");
        }
        return sb.toString().trim();
    }

    String reject(Runnable r) {
        try {
            r.run();
            return "ACCEPTED (unexpected)";
        } catch (RoomException e) {
            return "REJECTED [" + e.code() + "] " + e.getMessage();
        }
    }

    @Test
    void fullSession() {
        room = new Room("lng_demo", "host", new Room.Config(8.0, 15_000, 20, 10_000, true, true, 30_000));
        room.addMember("host", "Hana (host)", Role.HOST, Role.HOST, false);
        room.addMember("codj", "Dev (co-DJ)", Role.LISTENER, Role.CO_DJ, false);
        room.addMember("ana", "Ana (listener)", Role.LISTENER, Role.LISTENER, false);
        room.addMember("bo", "Bo (listener)", Role.LISTENER, Role.LISTENER, false);
        for (String u : List.of("host", "codj", "ana", "bo")) {
            inbox.put(u, new ArrayList<>());
        }
        long t = 0;

        say("=== 1. Two sessions join one lounge and the queue reorders live ===");
        for (String u : List.of("host", "codj", "ana", "bo")) {
            exec(r -> r.connect(u, 0));
        }
        say("Online: " + room.onlineCount() + " participants (host, co-DJ, Ana, Bo)");
        for (String id : List.of("t_2001", "t_2002", "t_2003", "t_2005", "t_2006")) {
            exec(r -> r.add("host", ref(id), 0));
        }
        exec(r -> r.start("host", 1_000));
        say("Host starts the session -> Now Playing: " + room.nowPlaying().title() + "; Next Up: " + queueLine());
        exec(r -> r.vote("ana", "t_2006", 2_000));
        exec(r -> r.vote("bo", "t_2006", 2_100));
        exec(r -> r.vote("ana", "t_2005", 2_200));
        say("Ana and Bo vote for 'Midnight Relay', Ana also for 'Static Bloom'");
        say("Queue now: " + queueLine());
        List<OutEvent> a = inbox.get("ana");
        List<OutEvent> b = inbox.get("bo");
        OutEvent lastA = a.get(a.size() - 1);
        OutEvent lastB = b.get(b.size() - 1);
        say("Ana's last broadcast seq=" + lastA.seq() + ", Bo's last broadcast seq=" + lastB.seq()
                + " -> identical: " + (lastA.seq() == lastB.seq() && lastA.name().equals(lastB.name())));
        assertEquals(lastA.seq(), lastB.seq());

        say("");
        say("=== 2. RBAC enforced server-side (a listener tries host/co-DJ actions) ===");
        say("Ana promotes a track:      " + reject(() -> room.promote("ana", "t_2003", 3_000)));
        say("Ana kicks Bo:              " + reject(() -> room.kick("ana", "bo", 3_000)));
        say("Ana resolves a Soundclash: " + reject(() -> room.resolveBattle("ana", 3_000)));
        say("Co-DJ locks the lounge:    " + reject(() -> room.setLocked("codj", true)));
        for (Permission p : Permission.values()) {
            StringBuilder sb = new StringBuilder();
            for (String role : List.of("listener", "co_dj", "host", "admin")) {
                sb.append(String.format("%-9s", p.allows(role) ? "allow" : "-"));
            }
            say(String.format("  %-16s listener/co_dj/host/admin: %s", p.name(), sb));
        }

        say("");
        say("=== 3. AI DJ segue: acoustic match reasoning, script, grounded Q&A ===");
        TrackFeatures current = features("t_2001");
        List<TrackFeatures> candidates = new ArrayList<>();
        for (String id : List.of("t_2002", "t_2003", "t_2004", "t_2005", "t_2006", "t_2007")) {
            candidates.add(features(id));
        }
        say("Current: Night Drive 124 BPM, 8A, energy 0.78. Candidates: all six other seed tracks.");
        SegueMatcher.SegueResult result = SegueMatcher.matchSegue(current, candidates, 0.5, 0.0, List.of(), 5, 8.0);
        for (var rej : result.rejected()) {
            say("  rejected " + meta.get(rej.trackId()).title() + ": " + rej.reasons());
        }
        for (var c : result.ranked()) {
            say(String.format("  ranked   %-14s score=%.3f  %s", c.track().title(), c.score(), c.matchReason()));
        }
        var chosen = result.selected();
        assertNotNull(chosen);
        TransitionWriter.Script script = new TransitionWriter().write(meta.get("t_2001"), meta.get(chosen.track().id()),
                chosen.matchReason(), TransitionWriter.Verbosity.CHATTY);
        say("Selected: " + chosen.track().title() + "  (hard rules satisfied: BPM within 8%, Camelot " + chosen.camelotRelation().wire() + ")");
        say("Transition script: \"" + script.text() + "\"");
        say("Grounded in: " + script.sourceEvents());
        Retriever retriever = new Retriever();
        List<TrackMeta> pool = new ArrayList<>(meta.values());
        for (String q : List.of("Was anything sampled in this track?", "What key is it in?", "Who played the drums?")) {
            Retriever.Answer ans = retriever.answer(q, meta.get("t_2001"), pool);
            say("Q: " + q + "\n   A: " + ans.text() + "\n   grounded=" + ans.grounded() + " citations=" + ans.citations());
        }
        assertTrue(!retriever.answer("Who played the drums?", meta.get("t_2001"), pool).grounded());
        say("Every candidate the matcher ranked satisfies the rules: "
                + result.ranked().stream().allMatch(c -> isValid(current, c.track())));
        assertTrue(result.ranked().stream().allMatch(c -> isValid(current, c.track())));

        say("");
        say("=== 4. Sentiment shift visibly changes what the AI picks ===");
        say("Same current track and candidates; only the room's mood differs.");
        for (var mood : new double[][] {{0.15, -0.2}, {0.5, 0.0}, {0.9, 0.2}}) {
            var r = SegueMatcher.matchSegue(current, candidates, mood[0], mood[1], List.of(), 5, 8.0);
            say(String.format("  mood=%.2f trend=%+.1f -> AI picks %s (energy %.2f)", mood[0], mood[1],
                    r.selected().track().title(), r.selected().track().energy()));
        }
        say("Now the same effect driven by real chat through the room (scripted burst):");
        say(String.format("  mood before chat: %.3f", room.sentiment(5_000)));
        String[] calm = {"this is boring, skip", "ugh not feeling it", "so dull honestly", "meh, I'm tired"};
        for (int i = 0; i < calm.length; i++) {
            long at = 5_000 + i * 1_000L;
            Result r = exec(x -> x.chat("ana", calm[(int) ((at - 5_000) / 1_000)], at));
            say(String.format("  \"%s\" -> message score %.3f", calm[i], (double) r.first("lounge.chat.message").payload().get("sentiment")));
        }
        double after = room.sentiment(9_000);
        say(String.format("  mood after a bored burst: %.3f", after));
        assertTrue(after < 0.5);
        String[] hype = {"THIS IS AMAZING 🔥🔥", "what a banger!!", "love this, turn it up!", "yesss best set ever 😍",
                "so good!!", "lets go!!! 🎉"};
        for (int i = 0; i < hype.length; i++) {
            int n = i;
            long at = 10_000 + i * 1_000L;
            Result r = exec(x -> x.chat("bo", hype[n], at));
            say(String.format("  \"%s\" -> message score %.3f", hype[i], (double) r.first("lounge.chat.message").payload().get("sentiment")));
        }
        double hyped = room.sentiment(16_000);
        say(String.format("  mood after a hype burst: %.3f (trend %+.2f)", hyped, room.aiContext(16_000).sentimentTrend()));
        assertTrue(hyped > after + 0.2);
        var calmPick = SegueMatcher.matchSegue(current, candidates, after, -0.1, List.of(), 5, 8.0).selected();
        var hypePick = SegueMatcher.matchSegue(current, candidates, hyped, 0.2, List.of(), 5, 8.0).selected();
        say("  AI pick in the bored room: " + calmPick.track().title() + "; in the hyped room: " + hypePick.track().title());

        say("");
        say("=== 5. A full Soundclash ===");
        exec(r -> r.openBattle("codj", 20_000));
        say("Co-DJ opens a Soundclash (30 s window).");
        say("Bo submits 'Static Bloom' (140 BPM, 3B): " + reject(() -> room.submitBattle("bo", ref("t_2005"), isValid(current, features("t_2005")), 20_100)));
        exec(r -> r.submitBattle("bo", ref("t_2002"), isValid(current, features("t_2002")), 20_200));
        exec(r -> r.submitBattle("ana", ref("t_2003"), isValid(current, features("t_2003")), 20_300));
        say("Bo submits 'Glass Harbor' and Ana submits 'Paper Lanterns' (both pass the BPM/key rules).");
        exec(r -> r.voteBattle("host", "t_2003", 21_000));
        exec(r -> r.voteBattle("codj", "t_2003", 21_100));
        exec(r -> r.voteBattle("bo", "t_2002", 21_200));
        Result done = exec(r -> r.tick(51_000));
        OutEvent resolved = done.first("lounge.soundclash.resolved");
        say("Window closes -> winner: " + meta.get((String) resolved.payload().get("winnerTrackId")).title()
                + " (" + resolved.payload().get("reason") + "), tally " + resolved.payload().get("tally"));
        say("Winner spliced into Next Up at the front: " + queueLine());
        assertEquals("t_2003", room.queueTrackIds().get(0));
        assertEquals(1, done.effectsOf(Effect.SaveBattle.class).size());

        say("");
        say("=== 6. Host disconnects: deterministic behaviour ===");
        exec(r -> r.disconnect("host", 60_000));
        exec(r -> r.tick(60_000 + 16_000));
        say("Host gone for 16 s -> state=" + room.hostState() + ", acting host=" + room.actingHostId());
        assertEquals(Room.HostState.ACTING_HOST, room.hostState());
        say("Acting host (co-DJ) can still curate: " + reject(() -> room.promote("codj", "t_2002", 77_000)).replace("ACCEPTED (unexpected)", "accepted"));
        exec(r -> r.disconnect("codj", 80_000));
        exec(r -> r.tick(80_000 + 16_000));
        say("Co-DJ also gone -> state=" + room.hostState());
        say("A listener tries to add a track while frozen: " + reject(() -> room.add("ana", ref("t_2007"), 97_000)));
        say("Chat still works while frozen: " + (exec(r -> r.chat("ana", "waiting for the host", 97_500)).first("lounge.chat.message") != null));
        exec(r -> r.connect("host", 100_000));
        say("Host reconnects (inside the same session, no duplicate join announcement for a quick reconnect) -> state=" + room.hostState());
        assertEquals(Room.HostState.PRESENT, room.hostState());
        say("Queue works again: " + reject(() -> room.add("ana", ref("t_2007"), 101_000)).replace("ACCEPTED (unexpected)", "accepted"));
    }

    private static boolean isValid(TrackFeatures from, TrackFeatures to) {
        try {
            SegueMatcher.assertValidSegue(from, to, 8.0);
            return true;
        } catch (SegueMatcher.InvalidSegueException e) {
            return false;
        }
    }
}
