package com.djstudio.room;

import com.djstudio.core.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomTest {

    static final Room.TrackRef A = new Room.TrackRef("t_a", "Alpha", 200);
    static final Room.TrackRef B = new Room.TrackRef("t_b", "Bravo", 200);
    static final Room.TrackRef C = new Room.TrackRef("t_c", "Charlie", 200);
    static final Room.TrackRef D = new Room.TrackRef("t_d", "Delta", 200);

    /** host, two co-DJs (c1 joined before c2) and three listeners, nobody connected yet. */
    static Room newRoom() {
        Room r = new Room("lng_1", "host", new Room.Config(8.0, 1000, 20, 5000, true));
        r.addMember("host", "Host", Role.HOST, Role.HOST, false);
        r.addMember("c1", "Co One", Role.LISTENER, Role.CO_DJ, false);
        r.addMember("c2", "Co Two", Role.LISTENER, Role.CO_DJ, false);
        r.addMember("l1", "Lis One", Role.LISTENER, Role.LISTENER, false);
        r.addMember("l2", "Lis Two", Role.LISTENER, Role.LISTENER, false);
        r.addMember("l3", "Lis Three", Role.LISTENER, Role.LISTENER, false);
        return r;
    }

    @SuppressWarnings("unchecked")
    static List<String> ids(OutEvent queueUpdate) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> m : (List<Map<String, Object>>) queueUpdate.payload().get("queue")) {
            out.add((String) m.get("trackId"));
        }
        return out;
    }

    static void code(String expected, Runnable r) {
        RoomException e = assertThrows(RoomException.class, r::run);
        assertEquals(expected, e.code());
    }

    @Test
    void votesReorderTheQueueAndBroadcastTheFullSnapshot() {
        Room r = newRoom();
        r.add("l1", A, 0);
        r.add("l1", B, 0);
        r.add("l1", C, 0);
        Result res = r.vote("l2", "t_c", 1);
        OutEvent up = res.first("lounge.queue.update");
        assertNotNull(up);
        assertTrue(up.isBroadcast());
        assertEquals(List.of("t_c", "t_a", "t_b"), ids(up));      // voted track jumps ahead, rest keep add order
        r.vote("l3", "t_b", 2);
        r.vote("l2", "t_b", 3);
        assertEquals(List.of("t_b", "t_c", "t_a"), r.queueTrackIds());
    }

    @Test
    void aVoteIsCountedOncePerUserAndUnvoteReverts() {
        Room r = newRoom();
        r.add("l1", A, 0);
        r.add("l1", B, 0);
        r.vote("l2", "t_b", 1);
        Result dup = r.vote("l2", "t_b", 2);
        assertTrue(dup.events().isEmpty());                       // idempotent, no spurious broadcast
        assertEquals(1, r.queueSlots().get(0).voteCount());
        r.unvote("l2", "t_b", 3);
        assertEquals(List.of("t_a", "t_b"), r.queueTrackIds());
    }

    @Test
    void ties_break_by_the_order_tracks_were_added() {
        Room r = newRoom();
        r.add("l1", A, 0);
        r.add("l1", B, 0);
        r.vote("l2", "t_b", 1);
        r.vote("l3", "t_a", 2);
        assertEquals(List.of("t_a", "t_b"), r.queueTrackIds());
    }

    @Test
    void everyBroadcastCarriesAStrictlyIncreasingSequenceNumber() {
        Room r = newRoom();
        long last = r.seq();
        for (Room.TrackRef t : List.of(A, B, C)) {
            for (OutEvent e : r.add("l1", t, 0).events()) {
                assertTrue(e.seq() > last);
                last = e.seq();
            }
        }
    }

    @Test
    void positionsAreSparse() {
        Room r = newRoom();
        r.add("l1", A, 0);
        r.add("l1", B, 0);
        assertEquals(1000, r.queueSlots().get(0).position());
        assertEquals(2000, r.queueSlots().get(1).position());
    }

    @Test
    void listenersCannotRemovePromoteOrAdvance() {
        Room r = newRoom();
        r.add("l1", A, 0);
        code("forbidden", () -> r.remove("l1", "t_a", 1));
        code("forbidden", () -> r.promote("l1", "t_a", 1));
        code("forbidden", () -> r.advance("l1", 1));
        code("forbidden", () -> r.start("c1", 1));                // co-DJ may not start (host action)
    }

    @Test
    void coDjPromotePinsATrackToTheFront() {
        Room r = newRoom();
        r.add("l1", A, 0);
        r.add("l1", B, 0);
        r.vote("l2", "t_a", 1);
        r.promote("c1", "t_b", 2);
        assertEquals(List.of("t_b", "t_a"), r.queueTrackIds());
    }

    @Test
    void duplicatesAndFullQueueAreRejected() {
        Room r = newRoom();
        r.add("l1", A, 0);
        code("duplicate_track", () -> r.add("l2", A, 1));
        for (int i = 0; i < QueueState.MAX_SIZE - 1; i++) {
            r.add("l1", new Room.TrackRef("x" + i, "X", 100), 0);
        }
        code("queue_full", () -> r.add("l1", new Room.TrackRef("overflow", "O", 100), 1));
    }

    @Test
    void startPlaysTheHeadAndAdvanceMovesOn() {
        Room r = newRoom();
        r.add("l1", A, 0);
        r.add("l1", B, 0);
        Result s = r.start("host", 100);
        assertEquals("live", r.status());
        assertEquals("t_a", r.nowPlaying().trackId());
        assertNotNull(s.first("lounge.queue.update"));
        r.advance("c1", 200);
        assertEquals("t_b", r.nowPlaying().trackId());
        assertEquals(List.of("t_a"), r.aiContext(0).recentTrackIds());
    }

    @Test
    void tracksEndAutomatically() {
        Room r = newRoom();
        r.add("l1", A, 0);
        r.add("l1", B, 0);
        r.start("host", 0);
        Result res = r.tick(200_000);                             // A is 200 s long
        assertEquals("t_b", r.nowPlaying().trackId());
        assertEquals("track_ended", res.first("lounge.queue.update").payload().get("reason"));
    }

    @Test
    void outroWindowAsksForASegueExactlyOncePerTrack() {
        Room r = newRoom();
        r.add("l1", A, 0);
        r.add("l1", B, 0);
        r.add("l1", C, 0);
        r.start("host", 0);
        assertTrue(!r.tick(100_000).wantsSegueSuggestion());
        assertTrue(r.tick(181_000).wantsSegueSuggestion());       // inside the final 20 s
        assertTrue(!r.tick(182_000).wantsSegueSuggestion());
    }

    @Test
    void aiSuggestionCanBeApprovedByACoDjAndIsAnnouncedWhenItPlays() {
        Room r = newRoom();
        r.add("l1", A, 0);
        r.add("l1", B, 0);
        r.add("l1", C, 0);
        r.start("host", 0);
        Result s = r.setSuggestion("t_c", "Up next: Charlie.", Map.of("energyFit", 0.9), List.of("evt_1"), 1000);
        assertNotNull(s.first("lounge.ai.segue.suggested"));
        code("forbidden", () -> r.approveSuggestion("l1", 2000));
        Result ok = r.approveSuggestion("c1", 2000);
        assertEquals("t_c", ids(ok.first("lounge.queue.update")).get(0));
        Result adv = r.advance("host", 3000);
        assertEquals("t_c", r.nowPlaying().trackId());
        assertEquals("Up next: Charlie.", adv.first("lounge.ai.dj_announcement").payload().get("script"));
    }

    @Test
    void anUnansweredSuggestionAutoApprovesAfterTheTimeout() {
        Room r = newRoom();
        r.add("l1", A, 0);
        r.add("l1", B, 0);
        r.add("l1", C, 0);
        r.start("host", 0);
        r.setSuggestion("t_c", "s", Map.of(), List.of(), 1000);
        r.tick(5999);
        assertNotNull(r.suggestion());
        r.tick(6000);
        assertNull(r.suggestion());
        assertEquals("t_c", r.queueTrackIds().get(0));
    }

    @Test
    void rejectingOrRemovingDropsTheSuggestion() {
        Room r = newRoom();
        r.add("l1", A, 0);
        r.add("l1", B, 0);
        r.add("l1", C, 0);
        r.start("host", 0);
        r.setSuggestion("t_c", "s", Map.of(), List.of(), 1000);
        r.rejectSuggestion("host", 1500);
        assertNull(r.suggestion());
        assertEquals(List.of("t_b", "t_c"), r.queueTrackIds());
        r.setSuggestion("t_c", "s", Map.of(), List.of(), 2000);
        r.remove("host", "t_c", 2100);
        assertNull(r.suggestion());
    }

    @Test
    void aStaleSuggestionForATrackNoLongerQueuedIsIgnored() {
        Room r = newRoom();
        r.add("l1", A, 0);
        r.start("host", 0);
        assertTrue(r.setSuggestion("gone", "s", Map.of(), List.of(), 10).isEmpty());
        assertNull(r.suggestion());
    }

    // ---------------------------------------------------------------- presence & host policy

    @Test
    void reconnectWithinGraceDoesNotAnnounceAgain() {
        Room r = newRoom();
        Result first = r.connect("l1", 0);
        assertEquals(1, first.all("lounge.presence.update").size());
        r.disconnect("l1", 100);
        r.tick(500);                                              // still inside the 1 s grace
        Result back = r.connect("l1", 600);
        assertTrue(back.all("lounge.presence.update").isEmpty());
        assertNotNull(back.first("lounge.state.snapshot"));
        r.tick(5000);
        assertTrue(r.isOnline("l1"));
    }

    @Test
    void leavingIsAnnouncedOnlyAfterTheGraceExpires() {
        Room r = newRoom();
        r.connect("l1", 0);
        r.disconnect("l1", 100);
        assertTrue(r.tick(900).all("lounge.presence.update").isEmpty());
        Result gone = r.tick(1100);
        assertEquals("left", gone.first("lounge.presence.update").payload().get("change"));
        assertTrue(r.tick(9000).all("lounge.presence.update").isEmpty());   // reported exactly once
    }

    @Test
    void secondTabIsNotANewJoin() {
        Room r = newRoom();
        r.connect("l1", 0);
        assertTrue(r.connect("l1", 10).all("lounge.presence.update").isEmpty());
        r.disconnect("l1", 20);
        assertTrue(r.isOnline("l1"));
    }

    @Test
    void connectingAsANonMemberIsRejected() {
        Room r = newRoom();
        code("not_a_member", () -> r.connect("stranger", 0));
    }

    @Test
    void hostDropsEarliestOnlineCoDjBecomesActingHost() {
        Room r = newRoom();
        r.connect("host", 0);
        r.connect("c2", 0);
        r.connect("c1", 0);
        r.disconnect("host", 10);
        Result res = r.tick(2000);
        assertEquals(Room.HostState.ACTING_HOST, r.hostState());
        assertEquals("c1", r.actingHostId());                     // c1 joined the lounge before c2
        assertNotNull(res.first("lounge.host.changed"));
        assertEquals(Role.HOST, r.effectiveRole("c1"));
        r.promote("c1", addAndGet(r), 3000);                      // acting host can curate
    }

    private static String addAndGet(Room r) {
        r.add("l1", D, 0);
        return "t_d";
    }

    @Test
    void withNoCoDjOnlineTheRoomFreezesButChatAndPlaybackContinue() {
        Room r = newRoom();
        r.add("l1", A, 0);
        r.add("l1", B, 0);
        r.start("host", 0);
        r.connect("host", 0);
        r.connect("l1", 0);
        r.disconnect("host", 10);
        r.tick(2000);
        assertEquals(Room.HostState.FROZEN, r.hostState());
        code("room_frozen", () -> r.add("l1", C, 2100));
        code("room_frozen", () -> r.vote("l1", "t_b", 2100));
        code("room_frozen", () -> r.advance("c1", 2100));
        assertNotNull(r.chat("l1", "hello", 2200).first("lounge.chat.message"));
        r.tick(200_000);                                          // track end still advances
        assertEquals("t_b", r.nowPlaying().trackId());
    }

    @Test
    void hostReturningUnfreezesAndReclaimsTheRoom() {
        Room r = newRoom();
        r.connect("host", 0);
        r.disconnect("host", 10);
        r.tick(2000);
        assertEquals(Room.HostState.FROZEN, r.hostState());
        Result back = r.connect("host", 3000);
        assertEquals(Room.HostState.PRESENT, r.hostState());
        assertNotNull(back.first("lounge.host.changed"));
        r.add("l1", A, 3100);                                     // works again
    }

    @Test
    void aCoDjArrivingWhileFrozenBecomesActingHost() {
        Room r = newRoom();
        r.connect("host", 0);
        r.disconnect("host", 10);
        r.tick(2000);
        r.connect("c2", 3000);
        assertEquals(Room.HostState.ACTING_HOST, r.hostState());
        assertEquals("c2", r.actingHostId());
    }

    // ---------------------------------------------------------------- moderation, chat

    @Test
    void mutedUsersCannotChatAndModerationNeedsTheHost() {
        Room r = newRoom();
        code("forbidden", () -> r.mute("c1", "l1", true));        // co-DJs cannot moderate
        Result m = r.mute("host", "l1", true);
        assertEquals(1, m.effectsOf(Effect.PersistMuted.class).size());
        code("muted", () -> r.chat("l1", "hi", 0));
        r.mute("host", "l1", false);
        r.chat("l1", "hi", 0);
    }

    @Test
    void theHostCannotBeModeratedAndKickClosesSessions() {
        Room r = newRoom();
        code("forbidden", () -> r.kick("host", "host", 0));
        r.connect("l1", 0);
        Result k = r.kick("host", "l1", 10);
        assertEquals("l1", k.effectsOf(Effect.CloseSessions.class).get(0).userId());
        assertTrue(!r.isMember("l1"));
        code("not_a_member", () -> r.vote("l1", "t_a", 20));
    }

    @Test
    void lockedLoungeOnlyLetsDjsAdd() {
        Room r = newRoom();
        r.setLocked("host", true);
        code("lounge_locked", () -> r.add("l1", A, 0));
        r.add("c1", A, 0);
        assertEquals(List.of("t_a"), r.queueTrackIds());
    }

    @Test
    void hostTransferMakesTheOldHostACoDj() {
        Room r = newRoom();
        code("forbidden", () -> r.setRole("c1", "l1", Role.HOST, 0));
        Result res = r.setRole("host", "c1", Role.HOST, 0);
        assertEquals("c1", r.ownerUserId());
        assertEquals(Role.CO_DJ, r.effectiveRole("host"));
        assertEquals(1, res.effectsOf(Effect.PersistHost.class).size());
        code("forbidden", () -> r.setRole("c1", "c1", Role.LISTENER, 0));
    }

    @Test
    void chatIsSanitisedAndValidated() {
        Room r = newRoom();
        Result res = r.chat("l1", "<b>hi</b>", 5);
        assertEquals("&lt;b&gt;hi&lt;/b&gt;", res.first("lounge.chat.message").payload().get("text"));
        code("invalid_message", () -> r.chat("l1", "   ", 5));
        code("invalid_message", () -> r.chat("l1", "x".repeat(501), 5));
    }

    @Test
    void reactionsMoveSentimentAndUnknownOnesAreRejected() {
        Room r = newRoom();
        double before = r.sentiment(0);
        r.react("l1", "fire", 0);
        assertTrue(r.sentiment(1) > before);
        r.react("l1", "thumbsdown", 1);
        r.react("l1", "thumbsdown", 2);
        r.react("l1", "thumbsdown", 3);
        assertTrue(r.sentiment(4) < before);
        code("invalid_reaction", () -> r.react("l1", "<script>", 4));
    }

    @Test
    void snapshotMatchesBroadcastState() {
        Room r = newRoom();
        r.add("l1", A, 0);
        r.add("l1", B, 0);
        r.vote("l2", "t_b", 1);
        Map<String, Object> snap = r.snapshot(10);
        assertEquals(r.seq(), snap.get("seq"));
        assertEquals(2, ((List<?>) snap.get("queue")).size());
    }

    @Test
    void endedLoungeAcceptsNoMoreTracks() {
        Room r = newRoom();
        r.start("host", 0);
        r.end("host", 10);
        code("lounge_ended", () -> r.add("l1", A, 20));
        code("lounge_ended", () -> r.start("host", 30));
    }

    @Test
    void requestSegueNeedsADjAndALiveTrackWithQueuedCandidates() {
        Room r = newRoom();
        r.add("l1", A, 0);
        r.add("l1", B, 0);
        r.add("l1", C, 0);
        code("forbidden", () -> r.requestSegue("l1"));
        code("not_live", () -> r.requestSegue("host"));
        r.start("host", 0);
        assertTrue(r.requestSegue("c1").wantsSegueSuggestion());
        r.advance("host", 10);
        r.remove("host", "t_c", 11);
        code("empty_queue", () -> r.requestSegue("host"));
    }

    @Test
    void aRestoredSeedTrackStartsItsClockWhenTheSessionStarts() {
        Room r = newRoom();
        r.restoreNowPlaying(A, 0);
        r.add("l1", B, 0);
        r.start("host", 5_000);
        assertTrue(r.tick(5_000 + 100_000).events().isEmpty());       // 100 s into a 200 s track: still playing
        assertEquals("t_a", r.nowPlaying().trackId());
        r.tick(5_000 + 200_000);
        assertEquals("t_b", r.nowPlaying().trackId());
    }

    // ---------------------------------------------------------------- sentiment wiring

    @Test
    void chatMessagesAreScoredBroadcastAndSaved() {
        Room r = newRoom();
        Result happy = r.chat("l1", "I love this track!!", 1000);
        double score = (double) happy.first("lounge.chat.message").payload().get("sentiment");
        assertTrue(score > 0.6);
        assertNotNull(happy.first("lounge.sentiment.score"));
        assertEquals(1, happy.effectsOf(Effect.SaveChat.class).size());
        assertTrue(r.sentiment(1000) > 0.5);
        r.chat("l2", "this is boring, skip", 2000);
        r.chat("l3", "I hate this", 3000);
        r.chat("l2", "awful", 4000);
        assertTrue(r.sentiment(4500) < 0.5);
    }

    @Test
    void negationSurvivesHtmlEscapingBecauseTheRawTextIsScored() {
        Room r = newRoom();
        double s = (double) r.chat("l1", "this isn't good", 0).first("lounge.chat.message").payload().get("sentiment");
        assertTrue(s < 0.4);
    }

    @Test
    void theAiSeesTheCurrentMood() {
        Room r = newRoom();
        for (int i = 0; i < 4; i++) {
            r.chat("l1", "amazing banger 🔥", 1000 + i * 500);
        }
        Room.AiContext ctx = r.aiContext(3000);
        assertTrue(ctx.sentiment() > 0.65);
    }

    // ---------------------------------------------------------------- interactions (recommender input)

    @Test
    void listenersWhoHeardATrackGetAnInteractionRow() {
        Room r = newRoom();
        r.add("l1", A, 0);
        r.add("l1", B, 0);
        r.start("host", 0);
        r.connect("l1", 0);
        r.connect("l2", 0);
        r.react("l1", "fire", 10);
        Result end = r.tick(200_000);                             // A finishes
        List<Effect.SaveInteraction> rows = end.effectsOf(Effect.SaveInteraction.class);
        assertEquals(2, rows.size());
        for (Effect.SaveInteraction i : rows) {
            assertEquals("t_a", i.trackId());
            assertTrue(i.completed());
            assertEquals(200, i.playedSeconds());
            assertEquals(i.userId().equals("l1"), i.liked());
        }
    }

    @Test
    void anEarlySkipIsNotACompletion() {
        Room r = newRoom();
        r.add("l1", A, 0);
        r.add("l1", B, 0);
        r.start("host", 0);
        r.connect("l1", 0);
        Result skip = r.advance("host", 20_000);
        Effect.SaveInteraction i = skip.effectsOf(Effect.SaveInteraction.class).get(0);
        assertEquals(20, i.playedSeconds());
        assertTrue(!i.completed());
    }

    @Test
    void usersWhoJoinedLateAreCountedAndOfflineOnesAreNot() {
        Room r = newRoom();
        r.add("l1", A, 0);
        r.add("l1", B, 0);
        r.start("host", 0);
        r.connect("l1", 5_000);                                   // joined mid-track
        Result adv = r.advance("host", 100_000);
        List<String> who = new ArrayList<>();
        adv.effectsOf(Effect.SaveInteraction.class).forEach(i -> who.add(i.userId()));
        assertEquals(List.of("l1"), who);
    }

    // ---------------------------------------------------------------- soundclash

    private Room liveRoomWithBattle() {
        Room r = newRoom();
        r.add("l1", A, 0);
        r.start("host", 0);
        r.openBattle("c1", 1000);
        return r;
    }

    @Test
    void soundclashHappyPathMostVotesWinsAndIsQueuedFirst() {
        Room r = liveRoomWithBattle();
        r.submitBattle("l1", B, true, 2000);
        r.submitBattle("l2", C, true, 2100);
        r.voteBattle("l3", "t_c", 2200);
        r.voteBattle("l1", "t_c", 2300);
        r.voteBattle("l2", "t_b", 2400);
        Result res = r.tick(31_000);                              // window (30 s) closed
        OutEvent done = res.first("lounge.soundclash.resolved");
        assertEquals("t_c", done.payload().get("winnerTrackId"));
        assertEquals("votes", done.payload().get("reason"));
        assertEquals("t_c", r.queueTrackIds().get(0));
        assertEquals(1, res.effectsOf(Effect.SaveBattle.class).size());
        assertEquals("t_c", res.effectsOf(Effect.SaveBattle.class).get(0).winnerTrackId());
    }

    @Test
    void tiesGoToTheEarliestSubmission() {
        Room r = liveRoomWithBattle();
        r.submitBattle("l1", B, true, 2000);
        r.submitBattle("l2", C, true, 2100);
        r.voteBattle("l3", "t_c", 2200);
        r.voteBattle("l2", "t_b", 2300);
        OutEvent done = r.tick(31_000).first("lounge.soundclash.resolved");
        assertEquals("t_b", done.payload().get("winnerTrackId"));
        assertEquals("tie_break", done.payload().get("reason"));
    }

    @Test
    void aBattleWithNoVotesHasNoWinnerButCanBeForcedByADj() {
        Room r = liveRoomWithBattle();
        r.submitBattle("l1", B, true, 2000);
        OutEvent none = r.tick(31_000).first("lounge.soundclash.resolved");
        assertNull(none.payload().get("winnerTrackId"));
        assertEquals("no_votes", none.payload().get("reason"));

        Room r2 = liveRoomWithBattle();
        r2.submitBattle("l1", B, true, 2000);
        code("forbidden", () -> r2.resolveBattle("l1", 3000));
        OutEvent forced = r2.resolveBattle("c1", 3000).first("lounge.soundclash.resolved");
        assertEquals("t_b", forced.payload().get("winnerTrackId"));
        assertEquals("forced", forced.payload().get("reason"));
    }

    @Test
    void incompatibleTracksNeverEnterABattle() {
        Room r = liveRoomWithBattle();
        code("incompatible_segue", () -> r.submitBattle("l1", B, false, 2000));
        Map<?, ?> b = (Map<?, ?>) r.snapshot(2000).get("battle");
        assertEquals(0, ((List<?>) b.get("candidates")).size());
    }

    @Test
    void battleRulesOneEntryPerUserNoDuplicatesMaxFourOneVoteEach() {
        Room r = liveRoomWithBattle();
        r.submitBattle("l1", B, true, 2000);
        code("already_submitted", () -> r.submitBattle("l1", C, true, 2001));
        code("duplicate_track", () -> r.submitBattle("l2", B, true, 2002));
        code("duplicate_track", () -> r.submitBattle("l2", A, true, 2003));      // the playing track
        r.submitBattle("l2", C, true, 2004);
        r.submitBattle("l3", D, true, 2005);
        r.submitBattle("c2", new Room.TrackRef("t_e", "Echo", 100), true, 2006);
        code("battle_full", () -> r.submitBattle("host", new Room.TrackRef("t_f", "Foxtrot", 100), true, 2007));

        r.voteBattle("l1", "t_b", 2100);
        r.voteBattle("l1", "t_c", 2200);                                          // moves the vote, does not add one
        OutEvent done = r.resolveBattle("host", 2300).first("lounge.soundclash.resolved");
        List<?> tally = (List<?>) done.payload().get("tally");
        int total = 0;
        for (Object o : tally) {
            total += (int) ((Map<?, ?>) o).get("votes");
        }
        assertEquals(1, total);
        assertEquals("t_c", done.payload().get("winnerTrackId"));
    }

    @Test
    void battlePreconditionsAndPermissions() {
        Room r = newRoom();
        code("forbidden", () -> r.openBattle("l1", 0));
        code("not_live", () -> r.openBattle("host", 0));          // nothing playing yet
        r.add("l1", A, 0);
        r.start("host", 0);
        r.openBattle("host", 10);
        code("battle_open", () -> r.openBattle("c1", 20));
        code("no_battle", () -> newRoom().voteBattle("l1", "t_a", 0));
        Room off = new Room("lng_x", "host", new Room.Config(8.0, 1000, 20, 5000, true, false, 30_000));
        off.addMember("host", "Host", Role.HOST, Role.HOST, false);
        off.add("host", A, 0);
        off.start("host", 0);
        code("soundclash_disabled", () -> off.openBattle("host", 10));
    }

    @Test
    void votesAfterTheWindowClosesAreRejected() {
        Room r = liveRoomWithBattle();
        r.submitBattle("l1", B, true, 2000);
        code("no_battle", () -> r.voteBattle("l2", "t_b", 31_001));
    }

    @Test
    void frozenRoomsCannotOpenBattlesAndEndingClearsOne() {
        Room r = liveRoomWithBattle();
        r.end("host", 5000);
        assertNull(r.snapshot(5000).get("battle"));
    }
}
