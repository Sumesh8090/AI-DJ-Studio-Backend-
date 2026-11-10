import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End-to-end load test for a RUNNING AI DJ Studio (JDK 17+ only, no dependencies):
 *
 *   java tools/LoadTest.java [--base http://localhost:8080] [--clients 30] [--rounds 100] [--ai 100]
 *
 * It creates a lounge, connects N WebSocket clients, then repeatedly makes one client vote/unvote and measures how
 * long until EVERY other client has received the resulting lounge.queue.update. Sender and receivers share one clock
 * (this process), so the numbers are real fan-out latency; the spread between the first and last receiver is the
 * practical "sync drift" between clients on the same machine. It then calls POST /ai/segue repeatedly to measure
 * AI latency end to end (HTTP + auth + database + matcher + script).
 *
 * Needs the demo seed data (SEED_ENABLED=true, SEED_DEMO_PASSWORD set) and the host account host@example.com.
 */
public class LoadTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String PASSWORD = "Load-Test-Pass-123";

    static String base = "http://localhost:8080";
    static String demoPassword = System.getenv().getOrDefault("SEED_DEMO_PASSWORD", "Demo-Password-123");
    static int clients = 30;
    static int rounds = 100;
    static int aiCalls = 100;

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--base" -> base = args[i + 1];
                case "--clients" -> clients = Integer.parseInt(args[i + 1]);
                case "--rounds" -> rounds = Integer.parseInt(args[i + 1]);
                case "--ai" -> aiCalls = Integer.parseInt(args[i + 1]);
                default -> { }
            }
        }
        System.out.printf("Target %s, %d clients, %d fan-out rounds, %d AI calls%n", base, clients, rounds, aiCalls);

        String hostToken = field(post("/api/v1/auth/login", null,
                "{\"email\":\"host@example.com\",\"password\":\"" + demoPassword + "\"}"), "accessToken");
        String created = post("/api/v1/lounges", hostToken, "{\"seedTrackId\":\"t_2001\",\"vibeProfile\":\"Load test\"}");
        String loungeId = field(created, "loungeId");
        String invite = field(created, "inviteCode");

        String run = Long.toString(System.currentTimeMillis(), 36);
        List<String> tokens = new ArrayList<>();
        tokens.add(hostToken);
        for (int i = 1; i < clients; i++) {
            String email = "load" + run + "_" + i + "@example.com";
            post("/api/v1/auth/register", null, "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                    + "\",\"displayName\":\"Load " + i + "\"}");
            String tok = field(post("/api/v1/auth/login", null,
                    "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"), "accessToken");
            post("/api/v1/lounges/join", tok, "{\"inviteCode\":\"" + invite + "\"}");
            tokens.add(tok);
        }

        AtomicLong round = new AtomicLong(0);
        long[] recv = new long[clients];
        CountDownLatch[] latch = new CountDownLatch[1];
        List<Client> conns = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            Client c = new Client(i, round, recv, latch);
            c.ws = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(
                    URI.create(base.replaceFirst("^http", "ws") + "/ws/lounges/" + loungeId + "?token=" + tokens.get(i)), c)
                    .get(10, TimeUnit.SECONDS);
            conns.add(c);
        }
        Thread.sleep(500);
        conns.get(0).send("queue.add", "{\"trackId\":\"t_2002\"}");
        Thread.sleep(500);

        // fan-out rounds: client 1 votes, then un-votes, alternately; every other client must see each change
        Client sender = conns.get(1);
        long[] fanOut = new long[rounds];
        long[] spread = new long[rounds];
        int timeouts = 0;
        for (int r = 0; r < rounds; r++) {
            latch[0] = new CountDownLatch(clients - 1);
            Arrays.fill(recv, 0);
            round.incrementAndGet();
            long t0 = System.nanoTime();
            sender.send(r % 2 == 0 ? "queue.vote" : "queue.unvote", "{\"trackId\":\"t_2002\"}");
            if (!latch[0].await(5, TimeUnit.SECONDS)) {
                timeouts++;
                continue;
            }
            long min = Long.MAX_VALUE;
            long max = 0;
            for (int i = 0; i < clients; i++) {
                if (i != 1 && recv[i] != 0) {
                    min = Math.min(min, recv[i]);
                    max = Math.max(max, recv[i]);
                }
            }
            fanOut[r] = max - t0;
            spread[r] = max - min;
            Thread.sleep(70);                       // stay under the 20 commands/second/connection limit
        }
        report("Queue broadcast, send -> last receiver", fanOut, timeouts);
        report("Spread between first and last receiver (sync drift)", spread, timeouts);

        long[] ai = new long[aiCalls];
        int aiFail = 0;
        for (int i = 0; i < aiCalls; i++) {
            long t0 = System.nanoTime();
            HttpResponse<String> res = send("POST", "/api/v1/lounges/" + loungeId + "/ai/segue", hostToken,
                    "{\"currentTrackId\":\"t_2001\",\"candidateTrackIds\":[\"t_2002\",\"t_2003\",\"t_2005\",\"t_2006\","
                            + "\"t_2007\"],\"currentSentimentScore\":0.6}");
            ai[i] = System.nanoTime() - t0;
            if (res.statusCode() != 200) {
                aiFail++;
            }
        }
        report("AI segue endpoint (HTTP, end to end)", ai, aiFail);

        long[] api = new long[aiCalls];
        int apiFail = 0;
        for (int i = 0; i < aiCalls; i++) {
            long t0 = System.nanoTime();
            HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(base + (i % 2 == 0
                    ? "/api/v1/tracks?limit=20" : "/api/v1/me/dashboard"))).header("Authorization", "Bearer " + hostToken)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            api[i] = System.nanoTime() - t0;
            if (res.statusCode() != 200) {
                apiFail++;
            }
        }
        report("Non-AI REST (tracks list + personal dashboard)", api, apiFail);
        long[] apiSorted = Arrays.stream(api).sorted().toArray();
        boolean apiOk = apiFail == 0 && p(apiSorted, 0.95) < 200_000_000L;

        long[] f = Arrays.stream(fanOut).filter(x -> x > 0).sorted().toArray();
        long[] a = Arrays.stream(ai).sorted().toArray();
        boolean syncOk = f.length > 0 && p(f, 0.95) < 100_000_000L && timeouts == 0;
        boolean aiOk = aiFail == 0 && p(a, 0.95) < 3_000_000_000L;
        boolean fanOk = f.length > 0 && p(f, 0.99) < 500_000_000L;
        System.out.printf("%nPRD targets: queue sync P95 < 100 ms: %s | fan-out P99 < 500 ms: %s | non-AI API P95 < 200 ms: %s"
                + " | AI P95 < 3 s: %s%n", syncOk ? "PASS" : "FAIL", fanOk ? "PASS" : "FAIL", apiOk ? "PASS" : "FAIL",
                aiOk ? "PASS" : "FAIL");
        conns.forEach(c -> c.ws.sendClose(WebSocket.NORMAL_CLOSURE, "done"));
        System.exit(syncOk && aiOk && apiOk && fanOk ? 0 : 1);
    }

    // ------------------------------------------------------------------ helpers

    static final class Client implements WebSocket.Listener {
        final int index;
        final AtomicLong round;
        final long[] recv;
        final CountDownLatch[] latch;
        final StringBuilder buf = new StringBuilder();
        long lastRound = 0;
        WebSocket ws;

        Client(int index, AtomicLong round, long[] recv, CountDownLatch[] latch) {
            this.index = index;
            this.round = round;
            this.recv = recv;
            this.latch = latch;
        }

        @Override
        public void onOpen(WebSocket w) {
            w.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                if (msg.startsWith("{\"event\":\"lounge.queue.update\"")) {
                    long now = System.nanoTime();
                    long r = round.get();
                    if (r != lastRound && index != 1 && latch[0] != null) {
                        lastRound = r;
                        recv[index] = now;
                        latch[0].countDown();
                    }
                }
            }
            w.request(1);
            return null;
        }

        void send(String event, String payload) throws Exception {
            ws.sendText("{\"event\":\"" + event + "\",\"payload\":" + payload + "}", true).get(5, TimeUnit.SECONDS);
        }
    }

    static String post(String path, String token, String body) throws Exception {
        HttpResponse<String> res = send("POST", path, token, body);
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException(path + " -> " + res.statusCode() + " " + res.body());
        }
        return res.body();
    }

    static HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (!m.find()) {
            throw new IllegalStateException("no field " + name + " in " + json);
        }
        return m.group(1);
    }

    static long p(long[] sorted, double q) {
        return sorted[(int) Math.min(sorted.length - 1, Math.floor(q * sorted.length))];
    }

    static void report(String name, long[] nanos, int failures) {
        long[] s = Arrays.stream(nanos).filter(x -> x > 0).sorted().toArray();
        if (s.length == 0) {
            System.out.printf("%-58s no successful samples (%d failures)%n", name, failures);
            return;
        }
        System.out.printf("%-58s n=%d  p50=%.1f ms  p95=%.1f ms  p99=%.1f ms  max=%.1f ms  failures=%d%n", name,
                s.length, p(s, 0.50) / 1e6, p(s, 0.95) / 1e6, p(s, 0.99) / 1e6, s[s.length - 1] / 1e6, failures);
    }
}
