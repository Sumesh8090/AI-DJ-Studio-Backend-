package com.djstudio.config;

import com.djstudio.core.Ids;
import com.djstudio.domain.User;
import com.djstudio.repo.TrackRepository;
import com.djstudio.repo.UserRepository;
import com.djstudio.service.TrackService;
import com.djstudio.web.dto.TrackDtos.TrackCreateRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the authored acoustic catalog and demo users when app.seed.enabled=true (APP_SEED_ENABLED=true).
 * Idempotent: re-running skips what already exists. Demo-user passwords come from SEED_DEMO_PASSWORD; if unset a
 * random one is generated and printed once, so nothing is hardcoded.
 */
@Component
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private record DemoUser(String email, String name, String role) {
    }

    private static final List<DemoUser> DEMO_USERS = List.of(
            new DemoUser("admin@example.com", "Demo Admin", "admin"),
            new DemoUser("host@example.com", "Demo Host", "host"),
            new DemoUser("codj@example.com", "Demo Co-DJ", "co_dj"),
            new DemoUser("listener@example.com", "Demo Listener", "listener"),
            new DemoUser("listener2@example.com", "Demo Listener 2", "listener"));

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final TrackService trackService;
    private final TrackRepository tracks;
    private final UserRepository users;
    private final PasswordEncoder encoder;

    public SeedRunner(AppProperties props, ObjectMapper mapper, Validator validator, TrackService trackService,
                      TrackRepository tracks, UserRepository users, PasswordEncoder encoder) {
        this.props = props;
        this.mapper = mapper;
        this.validator = validator;
        this.trackService = trackService;
        this.tracks = tracks;
        this.users = users;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int trackCount = seedTracks();
        int userCount = seedUsers();
        log.info("Seeded {} track(s) and {} demo user(s).", trackCount, userCount);
    }

    private int seedTracks() throws Exception {
        List<TrackCreateRequest> requests;
        try (InputStream in = new ClassPathResource("seed/tracks.json").getInputStream()) {
            requests = mapper.readValue(in, new TypeReference<List<TrackCreateRequest>>() { });
        }
        int created = 0;
        for (TrackCreateRequest req : requests) {
            // Same validation as the admin upload API.
            Set<ConstraintViolation<TrackCreateRequest>> violations = validator.validate(req);
            if (!violations.isEmpty()) {
                throw new ConstraintViolationException("Invalid seed track " + req.id(), violations);
            }
            if (tracks.existsById(req.id())) {
                continue;
            }
            trackService.create(req, "seed");
            created++;
        }
        return created;
    }

    private int seedUsers() {
        String configured = props.seed() == null ? null : props.seed().demoPassword();
        boolean generated = configured == null || configured.isBlank();
        String password = generated ? randomPassword() : configured;

        int created = 0;
        for (DemoUser d : DEMO_USERS) {
            if (!users.existsByEmail(d.email())) {
                users.save(new User(Ids.newId("u"), d.email(), encoder.encode(password), d.name(), d.role()));
                created++;
            }
        }
        if (created > 0 && generated) {
            // Printed once on purpose; deliberately not routed through the structured logger.
            System.out.println("Demo password for all new demo users (shown once): " + password);
        }
        return created;
    }

    private static String randomPassword() {
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
