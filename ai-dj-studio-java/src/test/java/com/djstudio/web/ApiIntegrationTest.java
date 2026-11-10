package com.djstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.djstudio.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end tests against a real MySQL 8.4 container (Flyway migrations run for real, so the schema's CHECK
 * constraints and foreign keys are exercised too). Skipped automatically when Docker is not available.
 */
@SpringBootTest(properties = {
        "app.jwt.secret=integration-test-secret-0123456789abcdef0123456789",
        "app.seed.enabled=true",
        "app.seed.demo-password=Demo-Password-123"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ApiIntegrationTest {

    private static final String PASSWORD = "Demo-Password-123";

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;
    @Autowired
    JwtService jwtService;

    // ----------------------------------------------------------------- helpers
    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                               String bearer, Object body) throws Exception {
        if (bearer != null) {
            req = req.header("Authorization", bearer);
        }
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
        }
        return mvc.perform(req);
    }

    private JsonNode json(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private JsonNode login(String email) throws Exception {
        return json(send(post("/api/v1/auth/login"), null, Map.of("email", email, "password", PASSWORD))
                .andExpect(status().isOk()));
    }

    /** Bearer header for one of the seeded demo users: admin | host | codj | listener | listener2. */
    private String as(String demo) throws Exception {
        return "Bearer " + login(demo + "@example.com").get("accessToken").asText();
    }

    private static final Map<String, String> ROLE_TO_DEMO =
            Map.of("listener", "listener", "co_dj", "codj", "host", "host", "admin", "admin");

    /** Registers a brand-new listener and returns {email, id, bearer}; keeps tests independent of each other. */
    private Map<String, String> newUser() throws Exception {
        String email = "u" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        JsonNode created = json(send(post("/api/v1/auth/register"), null,
                Map.of("email", email, "password", PASSWORD, "displayName", "Test User")).andExpect(status().isCreated()));
        Map<String, String> out = new HashMap<>();
        out.put("email", email);
        out.put("id", created.get("id").asText());
        out.put("bearer", "Bearer " + login(email).get("accessToken").asText());
        return out;
    }

    private static String newTrackId() {
        return "t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static Map<String, Object> trackBody(String id) {
        Map<String, Object> body = new HashMap<>();
        if (id != null) {
            body.put("id", id);
        }
        body.put("title", "Test Track");
        body.put("artist", "Tester");
        body.put("durationSeconds", 180);
        body.put("acoustic", Map.of("bpm", 122, "camelotKey", "8A", "energy", 0.7, "valence", 0.6));
        body.put("timeline", List.of(Map.of("ts", 5, "type", "trivia", "text", "A fact.")));
        return body;
    }

    private JsonNode createLounge(String bearer, Map<String, Object> overrides) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("seedTrackId", "t_2001", "vibeProfile", "High-Energy EDM"));
        body.putAll(overrides);
        return json(send(post("/api/v1/lounges"), bearer, body).andExpect(status().isCreated()));
    }

    // ------------------------------------------------------------------ health
    @Test
    void healthEndpointIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    // -------------------------------------------------------------------- auth
    @Test
    void registerLoginAndMe() throws Exception {
        Map<String, String> u = newUser();
        send(get("/api/v1/auth/me"), u.get("bearer"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(u.get("id")))
                .andExpect(jsonPath("$.role").value("listener"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        JsonNode tokens = login(u.get("email"));
        assertEquals("bearer", tokens.get("tokenType").asText());
        assertEquals(15 * 60, tokens.get("expiresIn").asInt());
    }

    @Test
    void duplicateEmailIsAConflict() throws Exception {
        Map<String, String> u = newUser();
        send(post("/api/v1/auth/register"), null,
                Map.of("email", u.get("email").toUpperCase(), "password", PASSWORD, "displayName", "Dup"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("email_taken"));
    }

    @Test
    void cannotSelfRegisterAsAdminOrCoDj() throws Exception {
        for (String role : List.of("admin", "co_dj")) {
            send(post("/api/v1/auth/register"), null,
                    Map.of("email", role + "@x.co", "password", PASSWORD, "displayName", "x", "role", role))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("validation_error"));
        }
    }

    @Test
    void weakPasswordGivesAClearFieldError() throws Exception {
        JsonNode body = json(send(post("/api/v1/auth/register"), null,
                Map.of("email", "a@b.co", "password", "short", "displayName", "x"))
                .andExpect(status().isUnprocessableEntity()));
        assertEquals("password", body.at("/error/fields/0/field").asText());
    }

    @Test
    void displayNameIsSanitised() throws Exception {
        String email = "x" + UUID.randomUUID().toString().substring(0, 8) + "@y.co";
        send(post("/api/v1/auth/register"), null,
                Map.of("email", email, "password", PASSWORD, "displayName", "<script>alert(1)</script>"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("&lt;script&gt;alert(1)&lt;/script&gt;"));
    }

    @Test
    void loginFailuresAreIndistinguishable() throws Exception {
        Map<String, String> u = newUser();
        String wrongPassword = send(post("/api/v1/auth/login"), null,
                Map.of("email", u.get("email"), "password", "nope-nope-nope"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknownUser = send(post("/api/v1/auth/login"), null,
                Map.of("email", "ghost@example.com", "password", "nope-nope-nope"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertEquals(wrongPassword, unknownUser);
    }

    @Test
    void missingInvalidAndExpiredTokensAreRejected() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("not_authenticated"));
        send(get("/api/v1/auth/me"), "Bearer garbage", null).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_token"));
        String expired = jwtService.createAccessToken("u_x", "admin", Instant.now().minus(3, ChronoUnit.HOURS)).token();
        send(get("/api/v1/auth/me"), "Bearer " + expired, null).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message").value("Token has expired."));
    }

    @Test
    void refreshTokenCannotBeUsedAsAnAccessToken() throws Exception {
        Map<String, String> u = newUser();
        String refresh = login(u.get("email")).get("refreshToken").asText();
        send(get("/api/v1/auth/me"), "Bearer " + refresh, null).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotatesAndReuseRevokesTheWholeFamily() throws Exception {
        Map<String, String> u = newUser();
        String old = login(u.get("email")).get("refreshToken").asText();
        JsonNode rotated = json(send(post("/api/v1/auth/refresh"), null, Map.of("refreshToken", old))
                .andExpect(status().isOk()));
        String fresh = rotated.get("refreshToken").asText();
        assertNotEquals(old, fresh);

        // Re-using the rotated token is treated as theft...
        send(post("/api/v1/auth/refresh"), null, Map.of("refreshToken", old)).andExpect(status().isUnauthorized());
        // ...and the newer token from that family is revoked too.
        send(post("/api/v1/auth/refresh"), null, Map.of("refreshToken", fresh)).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        Map<String, String> u = newUser();
        String refresh = login(u.get("email")).get("refreshToken").asText();
        send(post("/api/v1/auth/logout"), null, Map.of("refreshToken", refresh)).andExpect(status().isNoContent());
        send(post("/api/v1/auth/refresh"), null, Map.of("refreshToken", refresh)).andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------- RBAC
    @Test
    void unauthenticatedRequestsAreRejectedEverywhere() throws Exception {
        mvc.perform(get("/api/v1/tracks")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/tracks")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/lounges")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/lounges/join")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tracks/t_2001/acoustic-profile")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAdminsCanManageTracks() throws Exception {
        Map<String, Integer> expected = Map.of("listener", 403, "co_dj", 403, "host", 403, "admin", 201);
        for (Map.Entry<String, Integer> e : expected.entrySet()) {
            ResultActions r = send(post("/api/v1/tracks"), as(ROLE_TO_DEMO.get(e.getKey())), trackBody(newTrackId()));
            assertEquals(e.getValue().intValue(), r.andReturn().getResponse().getStatus(), e.getKey());
            if (e.getValue() == 403) {
                r.andExpect(jsonPath("$.error.code").value("forbidden"));
            }
        }
    }

    @Test
    void onlyHostsAndAdminsCanCreateLounges() throws Exception {
        Map<String, Integer> expected = Map.of("listener", 403, "co_dj", 403, "host", 201, "admin", 201);
        Map<String, Object> body = Map.of("seedTrackId", "t_2001", "vibeProfile", "Lo-Fi Chill");
        for (Map.Entry<String, Integer> e : expected.entrySet()) {
            int actual = send(post("/api/v1/lounges"), as(ROLE_TO_DEMO.get(e.getKey())), body)
                    .andReturn().getResponse().getStatus();
            assertEquals(e.getValue().intValue(), actual, e.getKey());
        }
    }

    @Test
    void everyRoleCanBrowseTracks() throws Exception {
        for (String demo : ROLE_TO_DEMO.values()) {
            send(get("/api/v1/tracks"), as(demo), null).andExpect(status().isOk());
        }
    }

    @Test
    void onlyAdminsCanChangeRolesAndTheChangeAppliesImmediately() throws Exception {
        Map<String, String> target = newUser();

        for (String demo : List.of("listener", "codj", "host")) {
            send(patch("/api/v1/admin/users/" + target.get("id") + "/role"), as(demo), Map.of("role", "admin"))
                    .andExpect(status().isForbidden());
        }
        String admin = as("admin");
        send(patch("/api/v1/admin/users/" + target.get("id") + "/role"), admin, Map.of("role", "admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("admin"));

        // Their existing token now carries admin powers...
        send(post("/api/v1/tracks"), target.get("bearer"), trackBody(newTrackId())).andExpect(status().isCreated());

        // ...and demoting them takes effect on the SAME token, because the role is re-read from the database.
        send(patch("/api/v1/admin/users/" + target.get("id") + "/role"), admin, Map.of("role", "listener"))
                .andExpect(status().isOk());
        send(post("/api/v1/tracks"), target.get("bearer"), trackBody(newTrackId())).andExpect(status().isForbidden());
    }

    @Test
    void adminCannotDemoteThemselves() throws Exception {
        String admin = as("admin");
        String id = json(send(get("/api/v1/auth/me"), admin, null)).get("id").asText();
        send(patch("/api/v1/admin/users/" + id + "/role"), admin, Map.of("role", "listener"))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------ tracks & acoustics
    @Test
    void acousticProfileMatchesThePrdShape() throws Exception {
        send(get("/api/v1/tracks/t_2001/acoustic-profile"), as("listener"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackId").value("t_2001"))
                .andExpect(jsonPath("$.title").value("Night Drive"))
                .andExpect(jsonPath("$.acoustic.bpm").value(124.0))
                .andExpect(jsonPath("$.acoustic.camelotKey").value("8A"))
                .andExpect(jsonPath("$.acoustic.energy").value(0.78))
                .andExpect(jsonPath("$.timeline[0].ts").value(12))
                .andExpect(jsonPath("$.timeline[0].type").value("sample_credit"))
                .andExpect(jsonPath("$.timeline[0].text").value("Vocal chop sampled from a 1981 soul record."));
    }

    @Test
    void unknownTrackIs404() throws Exception {
        send(get("/api/v1/tracks/t_nope/acoustic-profile"), as("listener"), null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("track_not_found"));
    }

    @Test
    void invalidAcousticUploadsAreRejectedWithClearMessages() throws Exception {
        String admin = as("admin");
        Map<String, Map<String, Object>> cases = new HashMap<>();
        cases.put("positive number", Map.of("bpm", -5, "camelotKey", "8A", "energy", 0.5, "valence", 0.5));
        cases.put("Camelot", Map.of("bpm", 120, "camelotKey", "13A", "energy", 0.5, "valence", 0.5));
        cases.put("Energy must be between 0.0 and 1.0", Map.of("bpm", 120, "camelotKey", "8A", "energy", 1.5, "valence", 0.5));
        cases.put("Valence must be between 0.0 and 1.0", Map.of("bpm", 120, "camelotKey", "8A", "energy", 0.5, "valence", -0.2));
        for (Map.Entry<String, Map<String, Object>> c : cases.entrySet()) {
            Map<String, Object> body = trackBody(newTrackId());
            body.put("acoustic", c.getValue());
            JsonNode err = json(send(post("/api/v1/tracks"), admin, body).andExpect(status().isUnprocessableEntity()));
            assertEquals("validation_error", err.at("/error/code").asText());
            assertTrue(err.at("/error/message").asText().contains(c.getKey()), c.getKey() + " in " + err);
            assertTrue(err.at("/error/fields/0/field").asText().startsWith("acoustic."), err.toString());
        }
    }

    @Test
    void nonNumericBpmNamesTheOffendingField() throws Exception {
        Map<String, Object> body = trackBody(newTrackId());
        body.put("acoustic", Map.of("bpm", "fast", "camelotKey", "8A", "energy", 0.5, "valence", 0.5));
        JsonNode err = json(send(post("/api/v1/tracks"), as("admin"), body).andExpect(status().isUnprocessableEntity()));
        assertEquals("acoustic.bpm", err.at("/error/fields/0/field").asText());
    }

    @Test
    void adminUploadThenFetchRoundTripsAndDuplicatesConflict() throws Exception {
        String admin = as("admin");
        String id = newTrackId();
        send(post("/api/v1/tracks"), admin, trackBody(id)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.trackId").value(id));
        send(get("/api/v1/tracks/" + id + "/acoustic-profile"), as("listener"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acoustic.camelotKey").value("8A"))
                .andExpect(jsonPath("$.timeline[0].text").value("A fact."));
        send(post("/api/v1/tracks"), admin, trackBody(id)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("track_exists"));
    }

    @Test
    void trackTextIsSanitisedOnWrite() throws Exception {
        Map<String, Object> body = trackBody(newTrackId());
        body.put("title", "<b>Hi</b>");
        send(post("/api/v1/tracks"), as("admin"), body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("&lt;b&gt;Hi&lt;/b&gt;"));
    }

    @Test
    void updatingAnAcousticProfileKeepsOrReplacesTheTimeline() throws Exception {
        String admin = as("admin");
        String id = newTrackId();
        send(post("/api/v1/tracks"), admin, trackBody(id)).andExpect(status().isCreated());
        Map<String, Object> acoustic = Map.of("bpm", 130, "camelotKey", "9b", "energy", 0.5, "valence", 0.5);

        send(put("/api/v1/tracks/" + id + "/acoustic-profile"), admin, Map.of("acoustic", acoustic))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acoustic.camelotKey").value("9B"))   // normalised
                .andExpect(jsonPath("$.timeline.length()").value(1));        // omitted -> kept

        Map<String, Object> clear = new HashMap<>();
        clear.put("acoustic", acoustic);
        clear.put("timeline", List.of());
        send(put("/api/v1/tracks/" + id + "/acoustic-profile"), admin, clear)
                .andExpect(status().isOk()).andExpect(jsonPath("$.timeline.length()").value(0));
    }

    @Test
    void trackListingFiltersByBpmAndKey() throws Exception {
        String listener = as("listener");
        JsonNode byKey = json(send(get("/api/v1/tracks?key=8a&size=200"), listener, null).andExpect(status().isOk()));
        List<String> ids = new ArrayList<>();
        byKey.forEach(t -> ids.add(t.get("id").asText()));
        assertTrue(ids.contains("t_2001") && ids.contains("t_2003"), ids.toString());
        assertTrue(!ids.contains("t_2002"), ids.toString());
        send(get("/api/v1/tracks?key=99Z"), listener, null).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void compatibleEndpointAppliesTheMatchingRules() throws Exception {
        JsonNode body = json(send(get("/api/v1/tracks/t_2001/compatible?sentiment=0.7"), as("listener"), null)
                .andExpect(status().isOk()));
        List<String> ranked = new ArrayList<>();
        body.get("ranked").forEach(c -> ranked.add(c.get("trackId").asText()));
        assertTrue(ranked.containsAll(List.of("t_2002", "t_2003", "t_2004")), ranked.toString());
        assertTrue(!ranked.contains("t_2005") && !ranked.contains("t_2006") && !ranked.contains("t_2007"), ranked.toString());

        for (JsonNode r : body.get("rejected")) {
            if (r.get("trackId").asText().equals("t_2006")) {
                assertEquals("key_clash", r.get("reasons").get(0).asText()); // tempo fine, key clashes
            }
        }
        JsonNode reason = body.get("ranked").get(0).get("matchReason");
        assertTrue(reason.has("bpmDeltaPct") && reason.has("camelotRelation") && reason.has("energyFit"));
        send(get("/api/v1/tracks/t_2001/compatible?tolerancePct=99"), as("listener"), null)
                .andExpect(status().isUnprocessableEntity());
    }

    // ----------------------------------------------------------------- lounges
    @Test
    void createLoungeMatchesThePrdContract() throws Exception {
        JsonNode created = createLounge(as("host"), Map.of("maxParticipants", 40, "bpmTolerancePct", 8,
                "soundclashEnabled", true, "aiVerbosity", "normal"));
        assertEquals(5, created.size());
        assertEquals("scheduled", created.get("status").asText());
        assertTrue(created.get("loungeId").asText().startsWith("lg_"));
        assertTrue(created.get("inviteCode").asText().matches("SP-[A-Z2-9]{4}"));
        assertTrue(created.get("createdAt").asText().endsWith("Z"));
    }

    @Test
    void createLoungeValidatesInput() throws Exception {
        String host = as("host");
        send(post("/api/v1/lounges"), host, Map.of("seedTrackId", "t_missing", "vibeProfile", "x"))
                .andExpect(status().isNotFound());
        send(post("/api/v1/lounges"), host, Map.of("seedTrackId", "t_2001", "vibeProfile", "x", "maxParticipants", 1))
                .andExpect(status().isUnprocessableEntity());
        send(post("/api/v1/lounges"), host, Map.of("seedTrackId", "t_2001", "vibeProfile", "x", "aiVerbosity", "loud"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void joinFlowEnforcesCapacityAndIsIdempotent() throws Exception {
        JsonNode lounge = createLounge(as("host"), Map.of("maxParticipants", 2));
        String code = lounge.get("inviteCode").asText();

        send(post("/api/v1/lounges/join"), as("listener"), Map.of("inviteCode", code.toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participantCount").value(2))
                .andExpect(jsonPath("$.loungeRole").value("listener"));
        send(post("/api/v1/lounges/join"), as("listener"), Map.of("inviteCode", code))
                .andExpect(status().isOk()).andExpect(jsonPath("$.participantCount").value(2)); // no duplicate seat
        send(post("/api/v1/lounges/join"), as("codj"), Map.of("inviteCode", code))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("lounge_full"));
    }

    @Test
    void badInviteCodes() throws Exception {
        send(post("/api/v1/lounges/join"), as("listener"), Map.of("inviteCode", "nope"))
                .andExpect(status().isUnprocessableEntity());
        send(post("/api/v1/lounges/join"), as("listener"), Map.of("inviteCode", "SP-AAAA"))
                .andExpect(status().isNotFound());
    }

    @Test
    void loungeStateIsVisibleToMembersAndAdminsOnly() throws Exception {
        JsonNode lounge = createLounge(as("host"), Map.of());
        String url = "/api/v1/lounges/" + lounge.get("loungeId").asText();
        String listener = as("listener2");

        send(get(url), listener, null).andExpect(status().isForbidden()); // not joined yet
        send(post("/api/v1/lounges/join"), listener, Map.of("inviteCode", lounge.get("inviteCode").asText()))
                .andExpect(status().isOk());
        send(get(url), listener, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentTrackId").value("t_2001"))
                .andExpect(jsonPath("$.queue.length()").value(0))
                .andExpect(jsonPath("$.participantCount").value(2))
                .andExpect(jsonPath("$.settings.bpmTolerancePct").value(8.0));
        send(get(url), as("host"), null).andExpect(status().isOk());
        send(get(url), as("admin"), null).andExpect(status().isOk());
        send(get("/api/v1/lounges/lg_nope"), as("admin"), null).andExpect(status().isNotFound());
    }

    /**
     * Capacity must hold under simultaneous joins: the lounge row is locked (SELECT ... FOR UPDATE), so with 2 free
     * seats and 6 racing users exactly 2 succeed and 4 get "lounge_full".
     */
    @Test
    void concurrentJoinsNeverExceedCapacity() throws Exception {
        JsonNode lounge = createLounge(as("host"), Map.of("maxParticipants", 3)); // host + 2 free seats
        String code = lounge.get("inviteCode").asText();

        List<String> bearers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            bearers.add(newUser().get("bearer"));
        }

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (String bearer : bearers) {
            results.add(pool.submit(() -> {
                go.await();
                return send(post("/api/v1/lounges/join"), bearer, Map.of("inviteCode", code))
                        .andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        int ok = 0;
        int full = 0;
        for (Future<Integer> f : results) {
            int s = f.get();
            if (s == 200) {
                ok++;
            } else if (s == 409) {
                full++;
            }
        }
        pool.shutdown();

        assertEquals(2, ok, "exactly the two free seats are filled");
        assertEquals(4, full, "everyone else is told the lounge is full");
        send(get("/api/v1/lounges/" + lounge.get("loungeId").asText()), as("admin"), null)
                .andExpect(jsonPath("$.participantCount").value(3));
    }

    @Test
    void repeatedFailedLoginsAreThrottledPerAccount() throws Exception {
        String email = "victim" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        for (int i = 0; i < 5; i++) {
            send(post("/api/v1/auth/login"), null, Map.of("email", email, "password", "wrong-password-" + i))
                    .andExpect(status().isUnauthorized());
        }
        send(post("/api/v1/auth/login"), null, Map.of("email", email, "password", "wrong-password-x"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("too_many_attempts"));
        // a different account from the same address is not locked out by that
        send(post("/api/v1/auth/login"), null, Map.of("email", "host@example.com", "password", PASSWORD))
                .andExpect(status().isOk());
    }
}
