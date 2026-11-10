package com.djstudio.perf;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djstudio.ai.MetaEntry;
import com.djstudio.ai.TrackMeta;
import com.djstudio.ai.TransitionWriter;
import com.djstudio.core.Role;
import com.djstudio.core.SegueMatcher;
import com.djstudio.core.TrackFeatures;
import com.djstudio.room.Room;
import com.djstudio.sentiment.SentimentClassifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * In-process timing of the CPU-bound parts (no network, no database, no JSON). It shows how much of each latency
 * budget the logic itself uses; end-to-end numbers need the load-test tool in tools/ against a running stack.
 * Bounds are deliberately generous so the test is stable on slow CI machines; the printed numbers are the useful part.
 */
class PerformanceTest {

    static String pct(long[] sortedNanos) {
        return String.format("p50=%.3f ms  p95=%.3f ms  p99=%.3f ms  max=%.3f ms",
                at(sortedNanos, 0.50), at(sortedNanos, 0.95), at(sortedNanos, 0.99),
                sortedNanos[sortedNanos.length - 1] / 1e6);
    }

    static double at(long[] sorted, double q) {
        return sorted[(int) Math.min(sorted.length - 1, Math.floor(q * sorted.length))] / 1e6;
    }

    @Test
    void roomOperationsStayInTheMicrosecondRange() {
        Room room = new Room("lng_p", "host", new Room.Config(8.0, 15_000, 20, 10_000, true));
        room.addMember("host", "Host", Role.HOST, Role.HOST, false);
        for (int i = 0; i < 200; i++) {
            room.addMember("u" + i, "U" + i, Role.LISTENER, Role.LISTENER, false);
            room.connect("u" + i, 0);
        }
        for (int t = 0; t < 50; t++) {
            room.add("host", new Room.TrackRef("t" + t, "T" + t, 200), 0);
        }
        Random rnd = new Random(7);
        for (int i = 0; i < 20_000; i++) {                       // warm-up so the JIT has compiled the hot paths
            room.vote("u" + rnd.nextInt(200), "t" + rnd.nextInt(50), i);
        }
        int n = 50_000;
        long[] nanos = new long[n];
        for (int i = 0; i < n; i++) {
            String u = "u" + rnd.nextInt(200);
            String t = "t" + rnd.nextInt(50);
            long s = System.nanoTime();
            if ((i & 1) == 0) {
                room.vote(u, t, i);
            } else {
                room.unvote(u, t, i);
            }
            nanos[i] = System.nanoTime() - s;
        }
        Arrays.sort(nanos);
        System.out.println("room vote/unvote (200 members, 50 queued): " + pct(nanos));
        assertTrue(at(nanos, 0.95) < 5.0, "P95 " + at(nanos, 0.95) + " ms");
    }

    @Test
    void aiSegueComputationIsFarInsideTheThreeSecondBudget() {
        Random rnd = new Random(3);
        String[] keys = {"8A", "9A", "7A", "8B", "3B", "12A"};
        TrackFeatures current = new TrackFeatures("cur", 124, "8A", 0.7, 0.6, "Current");
        List<TrackFeatures> cands = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            cands.add(new TrackFeatures("c" + i, 100 + rnd.nextInt(50), keys[rnd.nextInt(keys.length)],
                    rnd.nextDouble(), rnd.nextDouble(), "C" + i));
        }
        TransitionWriter writer = new TransitionWriter();
        TrackMeta from = new TrackMeta("cur", "Current", 124, "8A", 0.7, List.of());
        TrackMeta to = new TrackMeta("c0", "Next", 126, "9A", 0.8,
                List.of(new MetaEntry("1", "sample_credit", "The vocal chop is sampled from a 1978 record", 30)));
        Runnable once = () -> {
            var r = SegueMatcher.matchSegue(current, cands, 0.6, 0.1, List.of("c3", "c4"), 5, 8.0);
            writer.write(from, to, r.selected() == null ? java.util.Map.of() : r.selected().matchReason(),
                    TransitionWriter.Verbosity.CHATTY);
        };
        for (int i = 0; i < 20_000; i++) {
            once.run();
        }
        int n = 20_000;
        long[] nanos = new long[n];
        for (int i = 0; i < n; i++) {
            long s = System.nanoTime();
            once.run();
            nanos[i] = System.nanoTime() - s;
        }
        Arrays.sort(nanos);
        System.out.println("AI segue match + script (20 candidates): " + pct(nanos));
        assertTrue(at(nanos, 0.95) < 50.0, "P95 " + at(nanos, 0.95) + " ms");
    }

    @Test
    void sentimentClassifierHandlesThousandsOfMessagesPerSecond() {
        SentimentClassifier c = new SentimentClassifier();
        String[] msgs = {"I love this track!!", "not good, kind of messy", "what's the bpm?", "this slaps 🔥🔥",
                "so boring, skip", "can someone add t_2003"};
        for (int i = 0; i < 20_000; i++) {
            c.classify(msgs[i % msgs.length]);
        }
        int n = 100_000;
        long s = System.nanoTime();
        for (int i = 0; i < n; i++) {
            c.classify(msgs[i % msgs.length]);
        }
        double perSecond = n / ((System.nanoTime() - s) / 1e9);
        System.out.printf("sentiment classifier: %.0f messages/second (single thread)%n", perSecond);
        assertTrue(perSecond > 5_000, "only " + perSecond + " msg/s");
    }

    @Test
    void memoryPerRoomIsSmall() {
        System.gc();
        long before = used();
        List<Room> rooms = new ArrayList<>();
        for (int r = 0; r < 500; r++) {
            Room room = new Room("lng_" + r, "host", new Room.Config(8.0, 15_000, 20, 10_000, true));
            room.addMember("host", "Host", Role.HOST, Role.HOST, false);
            for (int i = 0; i < 40; i++) {
                room.addMember("u" + i, "U" + i, Role.LISTENER, Role.LISTENER, false);
                room.connect("u" + i, 0);
            }
            for (int t = 0; t < 50; t++) {
                room.add("host", new Room.TrackRef("t" + t, "T" + t, 200), 0);
            }
            rooms.add(room);
        }
        System.gc();
        long perRoomKb = (used() - before) / 500 / 1024;
        System.out.println("memory per room (40 members, 50 queued tracks): ~" + perRoomKb + " KB; rooms=" + rooms.size());
        assertTrue(perRoomKb < 512, perRoomKb + " KB per room");
    }

    private static long used() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}
