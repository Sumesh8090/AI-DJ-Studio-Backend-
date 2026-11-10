package com.djstudio.service;

import com.djstudio.core.Ids;
import com.djstudio.core.Sanitizer;
import com.djstudio.domain.DjLounge;
import com.djstudio.domain.LoungeParticipant;
import com.djstudio.repo.DjLoungeRepository;
import com.djstudio.repo.LoungeParticipantRepository;
import com.djstudio.repo.QueueEntryRepository;
import com.djstudio.repo.TrackRepository;
import com.djstudio.security.UserPrincipal;
import com.djstudio.web.ApiException;
import com.djstudio.web.dto.LoungeDtos.JoinResponse;
import com.djstudio.web.dto.LoungeDtos.LoungeCreateRequest;
import com.djstudio.web.dto.LoungeDtos.LoungeCreatedResponse;
import com.djstudio.web.dto.LoungeDtos.LoungeResponse;
import com.djstudio.web.dto.LoungeDtos.QueueEntryResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoungeService {

    private static final Logger log = LoggerFactory.getLogger(LoungeService.class);

    private final DjLoungeRepository lounges;
    private final LoungeParticipantRepository participants;
    private final QueueEntryRepository queue;
    private final TrackRepository tracks;

    public LoungeService(DjLoungeRepository lounges, LoungeParticipantRepository participants,
                         QueueEntryRepository queue, TrackRepository tracks) {
        this.lounges = lounges;
        this.participants = participants;
        this.queue = queue;
        this.tracks = tracks;
    }

    @Transactional
    public LoungeCreatedResponse create(UserPrincipal host, LoungeCreateRequest req) {
        if (!tracks.existsById(req.seedTrackId())) {
            throw new ApiException(404, "track_not_found", "Seed track " + req.seedTrackId() + " does not exist.");
        }
        String vibe;
        try {
            vibe = Sanitizer.sanitize(req.vibeProfile(), 60, "vibeProfile");
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("vibeProfile", e.getMessage());
        }

        DjLounge lounge = new DjLounge(
                Ids.newId("lg"), uniqueInviteCode(), host.id(), vibe,
                req.seedTrackId(), // the seed track is "Now Playing"; Next Up starts empty
                req.maxParticipants() != null ? req.maxParticipants() : 40,
                req.bpmTolerancePct() != null ? req.bpmTolerancePct() : 8.0,
                req.soundclashEnabled() == null || req.soundclashEnabled(),
                req.aiVerbosity() != null ? req.aiVerbosity() : "normal");
        lounges.saveAndFlush(lounge); // flush so the row exists before the participant FK insert
        participants.save(new LoungeParticipant(lounge.getId(), host.id(), "host"));

        // The lounge id doubles as the correlation id for every later event in this session (PRD 10.3).
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", lounge.getId())) {
            log.atInfo().addKeyValue("loungeId", lounge.getId()).addKeyValue("hostUserId", host.id())
                    .log("lounge.created");
        }
        return new LoungeCreatedResponse(lounge.getId(), lounge.getInviteCode(), lounge.getStatus(),
                lounge.getHostUserId(), lounge.getCreatedAt().truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * Joins by invite code. The lounge row is locked (SELECT ... FOR UPDATE) so two simultaneous joins cannot both
     * pass the capacity check. Joining twice is idempotent.
     */
    @Transactional
    public JoinResponse join(UserPrincipal user, String inviteCode) {
        String code;
        try {
            code = Ids.normalizeInviteCode(inviteCode);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("inviteCode", e.getMessage());
        }
        DjLounge lounge = lounges.findByInviteCodeForUpdate(code)
                .orElseThrow(() -> new ApiException(404, "lounge_not_found", "No lounge matches that invite code."));
        if ("ended".equals(lounge.getStatus())) {
            throw new ApiException(409, "lounge_ended", "This lounge has ended.");
        }

        LoungeParticipant member = participants.findByLoungeIdAndUserId(lounge.getId(), user.id()).orElse(null);
        if (member != null && member.getLeftAt() == null) { // already in the room
            return new JoinResponse(lounge.getId(), lounge.getStatus(),
                    participants.countByLoungeIdAndLeftAtIsNull(lounge.getId()), member.getLoungeRole());
        }
        if (lounge.isLocked() && member == null) {
            throw new ApiException(403, "lounge_locked", "The host has locked this lounge.");
        }
        if (participants.countByLoungeIdAndLeftAtIsNull(lounge.getId()) >= lounge.getMaxParticipants()) {
            throw new ApiException(409, "lounge_full",
                    "This lounge is full (" + lounge.getMaxParticipants() + " participants).");
        }

        if (member == null) {
            member = participants.save(new LoungeParticipant(lounge.getId(), user.id(), "listener"));
        } else { // rejoin after leaving
            member.rejoin();
        }
        long count = participants.countByLoungeIdAndLeftAtIsNull(lounge.getId());
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", lounge.getId())) {
            log.atInfo().addKeyValue("loungeId", lounge.getId()).addKeyValue("userId", user.id())
                    .addKeyValue("participantCount", count).log("lounge.joined");
        }
        return new JoinResponse(lounge.getId(), lounge.getStatus(), count, member.getLoungeRole());
    }

    @Transactional(readOnly = true)
    public LoungeResponse get(UserPrincipal user, String loungeId) {
        DjLounge lounge = lounges.findById(loungeId)
                .orElseThrow(() -> new ApiException(404, "lounge_not_found", "No lounge with id " + loungeId + "."));
        boolean member = participants.existsByLoungeIdAndUserIdAndLeftAtIsNull(lounge.getId(), user.id());
        if (!member && !"admin".equals(user.role())) {
            throw new ApiException(403, "not_a_participant", "Join this lounge with its invite code to view it.");
        }

        List<QueueEntryResponse> entries = queue.findByLoungeIdOrderByPositionAsc(lounge.getId()).stream()
                .map(q -> new QueueEntryResponse(q.getTrackId(), q.getPosition(), q.getVoteCount()))
                .toList();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("maxParticipants", lounge.getMaxParticipants());
        settings.put("bpmTolerancePct", lounge.getBpmTolerancePct());
        settings.put("soundclashEnabled", lounge.isSoundclashEnabled());
        settings.put("aiVerbosity", lounge.getAiVerbosity());

        return new LoungeResponse(lounge.getId(), lounge.getHostUserId(), lounge.getVibeProfile(), lounge.getStatus(),
                lounge.isLocked(), lounge.getCurrentTrackId(), entries,
                participants.countByLoungeIdAndLeftAtIsNull(lounge.getId()), settings,
                lounge.getCreatedAt().truncatedTo(ChronoUnit.SECONDS));
    }

    private String uniqueInviteCode() {
        for (int i = 0; i < 10; i++) {
            String code = Ids.newInviteCode();
            if (!lounges.existsByInviteCode(code)) {
                return code;
            }
        }
        throw new ApiException(503, "invite_code_unavailable", "Could not allocate an invite code; please retry.");
    }
}
