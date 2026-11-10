package com.djstudio.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djstudio.core.Role;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Many threads hammer one room at once through the same "single writer, deliver while holding the lock" pattern that
 * the gateway's RoomRuntime uses. Proves the room's consistency guarantees under contention: every client receives
 * the same broadcasts in the same order with no gaps, and the final state is exactly what the votes imply.
 *
 * (This exercises the Room logic and the locking pattern; the real sockets are covered by the integration tests.)
 */
class RoomConcurrencyTest {

    static final int LISTENERS = 40;
    static final int TRACKS = 10;

    /** Mirrors RoomRuntime.execute: apply, then deliver every event to the matching clients, under one lock. */
    static final class Harness {
        final Room room;
        final Map<String, List<OutEvent>> inbox = new HashMap<>();

        Harness(Room room) {
            this.room = room;
        }

        synchronized Result exec(Function<Room, Result> op) {
            Result r = op.apply(room);
            for (OutEvent e : r.events()) {
                for (var entry : inbox.entrySet()) {
                    if (e.isBroadcast() || entry.getKey().equals(e.toUserId())) {
                        entry.getValue().add(e);
                    }
                }
            }
            return r;
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void concurrentVotersAndChattersSeeOneConsistentHistory() throws Exception {
        Room room = new Room("lng_c", "host", new Room.Config(8.0, 15_000, 20, 10_000, true));
        room.addMember("host", "Host", Role.HOST, Role.HOST, false);
        List<String> users = new ArrayList<>();
        for (int i = 0; i < LISTENERS; i++) {
            String u = "u" + i;
            users.add(u);
            room.addMember(u, "User " + i, Role.LISTENER, Role.LISTENER, false);
        }
        Harness h = new Harness(room);
        h.inbox.put("host", new ArrayList<>());
        for (String u : users) {
            h.inbox.put(u, new ArrayList<>());
        }
        h.exec(r -> r.connect("host", 0));
        for (String u : users) {
            h.exec(r -> r.connect(u, 0));
        }
        for (int t = 0; t < TRACKS; t++) {
            int n = t;
            h.exec(r -> r.add("host", new Room.TrackRef("t" + n, "Track " + n, 200), 0));
        }
        long startSeq = room.seq();
        h.inbox.values().forEach(List::clear);

        // user i votes for track j when (i*7 + j*3) % 5 != 0, in a per-thread random order; plus 3 chat messages each
        int[] expected = new int[TRACKS];
        for (int i = 0; i < LISTENERS; i++) {
            for (int j = 0; j < TRACKS; j++) {
                if ((i * 7 + j * 3) % 5 != 0) {
                    expected[j]++;
                }
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(LISTENERS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < LISTENERS; i++) {
            int me = i;
            futures.add(pool.submit(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                List<Integer> order = new ArrayList<>();
                for (int j = 0; j < TRACKS; j++) {
                    order.add(j);
                }
                java.util.Collections.shuffle(order, new Random(me));
                for (int j : order) {
                    if ((me * 7 + j * 3) % 5 != 0) {
                        h.exec(r -> r.vote("u" + me, "t" + j, 1));
                        h.exec(r -> r.vote("u" + me, "t" + j, 2));      // duplicate vote: must change nothing
                    }
                }
                for (int c = 0; c < 3; c++) {
                    int n = c;
                    h.exec(r -> r.chat("u" + me, "great track number " + n, 10 + n));
                }
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // 1) final state is exactly what the votes imply
        for (int j = 0; j < TRACKS; j++) {
            int votes = 0;
            for (QueueState.Slot s : room.queueSlots()) {
                if (s.trackId().equals("t" + j)) {
                    votes = s.voteCount();
                }
            }
            assertEquals(expected[j], votes, "votes for t" + j);
        }

        // 2) every client saw the identical, gap-free sequence of broadcasts
        List<OutEvent> reference = h.inbox.get("host");
        assertTrue(reference.size() > LISTENERS * 3, "should have many events");
        long next = startSeq + 1;
        for (OutEvent e : reference) {
            assertEquals(next++, e.seq(), "host sequence must be contiguous");
        }
        assertEquals(startSeq + reference.size(), room.seq());
        for (String u : users) {
            List<OutEvent> mine = h.inbox.get(u);
            assertEquals(reference.size(), mine.size(), u + " missed events");
            for (int k = 0; k < mine.size(); k++) {
                assertEquals(reference.get(k).seq(), mine.get(k).seq());
                assertEquals(reference.get(k).name(), mine.get(k).name());
            }
        }

        // 3) every queue snapshot that was ever broadcast obeys the ordering rule (votes never increase down the list,
        //    equal votes keep the order the tracks were added)
        for (OutEvent e : reference) {
            if (!e.name().equals("lounge.queue.update")) {
                continue;
            }
            List<Map<String, Object>> q = (List<Map<String, Object>>) e.payload().get("queue");
            assertEquals(TRACKS, q.size(), "a snapshot is always the full queue");
            for (int k = 1; k < q.size(); k++) {
                int prev = (int) q.get(k - 1).get("voteCount");
                int cur = (int) q.get(k).get("voteCount");
                assertTrue(prev >= cur, "snapshot at seq " + e.seq() + " is out of order");
                if (prev == cur) {
                    int a = Integer.parseInt(((String) q.get(k - 1).get("trackId")).substring(1));
                    int b = Integer.parseInt(((String) q.get(k).get("trackId")).substring(1));
                    assertTrue(a < b, "ties must keep add order");
                }
            }
        }
    }
}
