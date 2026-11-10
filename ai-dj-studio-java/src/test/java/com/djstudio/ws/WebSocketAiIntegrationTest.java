package com.djstudio.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real WebSocket clients against a running server and a real MySQL (Testcontainers; skipped without Docker).
 * Covers the Week 2 acceptance points: two clients see the same atomic queue reorder, RBAC on commands,
 * handshake authentication, and the AI segue / Q&A endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=integration-test-secret-0123456789abcdef0123456789",
        "app.seed.enabled=true",
        "app.seed.demo-password=Demo-Password-123"
})
@Testcontainers(disabledWithoutDocker = true)
class WebSocketAiIntegrationTest {

    private static final String PASSWORD = "Demo-Password-123";

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @LocalServerPort
    int port;

    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    // ------------------------------------------------------------------ helpers

    private JsonNode post(String path, String token, Object body, int expectedStatus) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, res.statusCode(), res.body());
        return om.readTree(res.body());
    }

    private JsonNode get(String path, String token, int expectedStatus) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, res.statusCode(), res.body());
        return om.readTree(res.body());
    }

    private String login(String demo) throws Exception {
        return post("/api/v1/auth/login", null, Map.of("email", demo + "@example.com", "password", PASSWORD), 200)
                .get("accessToken").asText();
    }

    /** host creates a lounge seeded with t_2001; the listener joins it. Returns {loungeId, inviteCode}. */
    private String[] lounge(String hostToken, String... joiners) throws Exception {
        JsonNode created = post("/api/v1/lounges", hostToken,
                Map.of("seedTrackId", "t_2001", "vibeProfile", "Late Night Deep House"), 201);
        String id = created.get("loungeId").asText();
        String code = created.get("inviteCode").asText();
        for (String j : joiners) {
            post("/api/v1/lounges/join", j, Map.of("inviteCode", code), 200);
        }
        return new String[] {id, code};
    }

    private final class Client implements WebSocket.Listener {
        final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
        final StringBuilder partial = new StringBuilder();
        WebSocket socket;

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                try {
                    inbox.add(om.readTree(partial.toString()));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                partial.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        void send(String event, Object payload) throws Exception {
            socket.sendText(om.writeValueAsString(Map.of("event", event, "id", "c-" + System.nanoTime(),
                    "payload", payload)), true).get(5, TimeUnit.SECONDS);
        }

        /** Next message with this event name (others are skipped), or fails after 5 s. */
        JsonNode await(String event) throws Exception {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                JsonNode m = inbox.poll(200, TimeUnit.MILLISECONDS);
                if (m != null && event.equals(m.get("event").asText())) {
                    return m;
                }
            }
            throw new AssertionError("Timed out waiting for " + event);
        }
    }

    private Client connect(String loungeId, String token) throws Exception {
        Client c = new Client();
        c.socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/lounges/" + loungeId + "?token=" + token), c)
                .get(5, TimeUnit.SECONDS);
        c.await("lounge.state.snapshot");
        return c;
    }

    private static List<String> trackIds(JsonNode queue) {
        List<String> ids = new java.util.ArrayList<>();
        queue.forEach(n -> ids.add(n.get("trackId").asText()));
        return ids;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void twoClientsSeeTheSameAtomicQueueReorder() throws Exception {
        String host = login("host");
        String listener = login("listener");
        String[] l = lounge(host, listener);
        Client h = connect(l[0], host);
        Client x = connect(l[0], listener);

        h.send("queue.add", Map.of("trackId", "t_2002"));
        h.send("queue.add", Map.of("trackId", "t_2003"));
        h.await("lounge.queue.update");
        h.await("lounge.queue.update");
        x.await("lounge.queue.update");
        x.await("lounge.queue.update");

        x.send("queue.vote", Map.of("trackId", "t_2003"));
        JsonNode atHost = h.await("lounge.queue.update");
        JsonNode atListener = x.await("lounge.queue.update");

        assertEquals(List.of("t_2003", "t_2002"), trackIds(atHost.get("payload").get("queue")));
        assertEquals(atHost.toString().replaceAll("\"ts\":\\d+", ""), atListener.toString().replaceAll("\"ts\":\\d+", ""),
                "both clients must receive the identical broadcast");
        assertEquals(atHost.get("seq").asLong(), atListener.get("seq").asLong());
    }

    @Test
    void listenersCannotUseDjCommands() throws Exception {
        String host = login("host");
        String listener = login("listener");
        String[] l = lounge(host, listener);
        Client x = connect(l[0], listener);
        x.send("queue.add", Map.of("trackId", "t_2002"));
        x.await("lounge.queue.update");
        x.send("queue.promote", Map.of("trackId", "t_2002"));
        assertEquals("forbidden", x.await("lounge.error").get("payload").get("code").asText());
        x.send("mod.kick", Map.of("userId", "anyone"));
        assertEquals("forbidden", x.await("lounge.error").get("payload").get("code").asText());
    }

    @Test
    void handshakeRejectsBadTokensAndNonMembers() throws Exception {
        String host = login("host");
        String outsider = login("listener2");
        String[] l = lounge(host);
        ExecutionException bad = assertThrows(ExecutionException.class, () -> connect(l[0], "not-a-token"));
        assertNotNull(bad.getCause());
        assertThrows(ExecutionException.class, () -> connect(l[0], outsider));   // authenticated but not a member
    }

    @Test
    void aSecondTabIsNotAnotherJoinAndChatIsSanitised() throws Exception {
        String host = login("host");
        String listener = login("listener");
        String[] l = lounge(host, listener);
        Client h = connect(l[0], host);
        Client x1 = connect(l[0], listener);
        h.await("lounge.presence.update");
        Client x2 = connect(l[0], listener);              // same user, second connection
        x1.send("chat.send", Map.of("text", "<b>hi</b>"));
        assertEquals("&lt;b&gt;hi&lt;/b&gt;", h.await("lounge.chat.message").get("payload").get("text").asText());
        assertEquals("&lt;b&gt;hi&lt;/b&gt;", x2.await("lounge.chat.message").get("payload").get("text").asText());
    }

    @Test
    void aiSegueIsRuleCompliantAndRestrictedToDjs() throws Exception {
        String host = login("host");
        String listener = login("listener");
        String[] l = lounge(host, listener);
        String path = "/api/v1/lounges/" + l[0] + "/ai/segue";

        JsonNode ok = post(path, host, Map.of("currentTrackId", "t_2001", "currentTs", 200.0,
                "candidateTrackIds", List.of("t_2002", "t_2003", "t_2005", "t_2006", "t_2007"),
                "currentSentimentScore", 0.7), 200);
        String chosen = ok.get("selectedTrackId").asText();
        assertTrue(List.of("t_2002", "t_2003").contains(chosen), "only BPM+key compatible tracks may be chosen: " + chosen);
        assertFalse(ok.get("transitionScript").asText().isBlank());
        assertTrue(ok.get("matchReason").has("camelotRelation"));
        assertTrue(ok.has("sourceEvents"));

        post(path, host, Map.of("currentTrackId", "t_2001", "candidateTrackIds", List.of("t_2005", "t_2006", "t_2007")), 422);
        post(path, listener, Map.of("currentTrackId", "t_2001", "candidateTrackIds", List.of("t_2002")), 403);
        post(path, host, Map.of("currentTrackId", "t_2001", "candidateTrackIds", List.of()), 422);
    }

    @Test
    void aiAnswersAreGroundedOrDecline() throws Exception {
        String host = login("host");
        String listener = login("listener");
        String[] l = lounge(host, listener);
        String path = "/api/v1/lounges/" + l[0] + "/ai/ask";

        JsonNode tempo = post(path, listener, Map.of("question", "What is the BPM?"), 200);
        assertTrue(tempo.get("grounded").asBoolean());
        assertTrue(tempo.get("answer").asText().contains("124 BPM"));

        JsonNode none = post(path, listener, Map.of("question", "Who played the bass guitar?"), 200);
        assertFalse(none.get("grounded").asBoolean());
        assertTrue(none.get("citations").isEmpty());
    }

    // ------------------------------------------------------------------ Week 3

    @Test
    void chatCarriesASentimentScore() throws Exception {
        String host = login("host");
        String listener = login("listener");
        String[] l = lounge(host, listener);
        Client x = connect(l[0], listener);
        x.send("chat.send", Map.of("text", "I love this track!!"));
        JsonNode msg = x.await("lounge.chat.message");
        assertTrue(msg.get("payload").get("sentiment").asDouble() > 0.6);
        assertTrue(x.await("lounge.sentiment.score").get("payload").get("score").asDouble() > 0.5);
    }

    @Test
    void soundclashEndToEndThenAnalyticsAndRecommendations() throws Exception {
        String host = login("host");
        String listener = login("listener");
        String admin = login("admin");
        String[] l = lounge(host, listener);
        Client h = connect(l[0], host);
        Client x = connect(l[0], listener);

        h.send("lounge.start", Map.of());
        h.await("lounge.status");
        x.send("soundclash.open", Map.of());
        assertEquals("forbidden", x.await("lounge.error").get("payload").get("code").asText());
        h.send("soundclash.open", Map.of());
        x.await("lounge.soundclash.open");

        x.send("soundclash.submit", Map.of("trackId", "t_2005"));          // 140 BPM / 3B: breaks both rules
        assertEquals("incompatible_segue", x.await("lounge.error").get("payload").get("code").asText());
        x.send("soundclash.submit", Map.of("trackId", "t_2002"));
        h.send("soundclash.submit", Map.of("trackId", "t_2003"));
        x.await("lounge.soundclash.update");
        x.send("soundclash.vote", Map.of("trackId", "t_2003"));
        h.send("soundclash.vote", Map.of("trackId", "t_2003"));
        h.send("soundclash.resolve", Map.of());
        JsonNode done = x.await("lounge.soundclash.resolved");
        assertEquals("t_2003", done.get("payload").get("winnerTrackId").asText());

        x.send("chat.send", Map.of("text", "what a banger 🔥"));
        x.await("lounge.chat.message");

        // analytics are written in one-second batches: poll briefly
        JsonNode a = null;
        for (int i = 0; i < 25; i++) {
            a = get("/api/v1/lounges/" + l[0] + "/analytics", host, 200);
            if (a.get("soundclash").get("battles").asLong() >= 1 && a.get("chatMessages").asLong() >= 1
                    && a.get("peakConcurrent").asLong() >= 2 && !a.get("durationSeconds").isNull()) {
                break;
            }
            Thread.sleep(300);
        }
        assertEquals(1, a.get("soundclash").get("battles").asLong());
        assertEquals(1, a.get("soundclash").get("withWinner").asLong());
        assertTrue(a.get("chatMessages").asLong() >= 1);
        assertTrue(a.get("peakConcurrent").asLong() >= 2);             // host and listener were online together
        assertTrue(a.get("durationSeconds").asLong() >= 0);
        assertTrue(a.has("aiQuestions"));
        assertTrue(a.get("sentiment").get("average").asDouble() > 0.5);
        get("/api/v1/lounges/" + l[0] + "/analytics", listener, 403);              // listeners cannot see it
        get("/api/v1/analytics/overview", admin, 200);
        get("/api/v1/analytics/overview", listener, 403);

        post("/api/v1/interactions", listener, Map.of("trackId", "t_2002", "playedSeconds", 180, "completed", true,
                "liked", true), 201);
        JsonNode recs = get("/api/v1/recommendations?limit=3", listener, 200);
        assertEquals(3, recs.get("tracks").size());
        recs.get("tracks").forEach(t -> assertFalse("t_2002".equals(t.get("trackId").asText())));
        get("/api/v1/recommendations?limit=0", listener, 422);

        // personal dashboard: the listener's history, taste profile (from the interaction just recorded) and rejoin list
        JsonNode me = get("/api/v1/me/dashboard", listener, 200);
        assertTrue(me.get("lounges").size() >= 1);
        assertEquals(1, me.get("tasteProfile").get("tracks").asInt());
        assertTrue(me.get("activeRooms").size() >= 1);
    }
}
