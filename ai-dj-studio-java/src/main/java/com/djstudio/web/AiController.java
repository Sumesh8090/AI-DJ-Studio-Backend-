package com.djstudio.web;

import com.djstudio.ai.AiDjService;
import com.djstudio.ai.Retriever;
import com.djstudio.ai.TransitionWriter;
import com.djstudio.core.Permission;
import com.djstudio.core.Role;
import com.djstudio.core.Sanitizer;
import com.djstudio.domain.DjLounge;
import com.djstudio.domain.LoungeParticipant;
import com.djstudio.repo.DjLoungeRepository;
import com.djstudio.repo.LoungeParticipantRepository;
import com.djstudio.security.UserPrincipal;
import com.djstudio.web.dto.AiDtos.AskRequest;
import com.djstudio.web.dto.AiDtos.AskResponse;
import com.djstudio.web.dto.AiDtos.SegueRequest;
import com.djstudio.web.dto.AiDtos.SegueResponse;
import com.djstudio.ws.RoomService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AI DJ over REST (PRD 7.3.3). Both endpoints are read-only: they never change the queue. */
@RestController
@RequestMapping("/api/v1/lounges/{loungeId}/ai")
public class AiController {

    private final AiDjService ai;
    private final DjLoungeRepository lounges;
    private final LoungeParticipantRepository participants;
    private final RoomService rooms;

    public AiController(AiDjService ai, DjLoungeRepository lounges, LoungeParticipantRepository participants,
                        RoomService rooms) {
        this.ai = ai;
        this.lounges = lounges;
        this.participants = participants;
        this.rooms = rooms;
    }

    /** Co-DJ or host: ask the AI which candidate to play next and what to say. */
    @PostMapping("/segue")
    public SegueResponse segue(@AuthenticationPrincipal UserPrincipal user, @PathVariable String loungeId,
                               @Valid @RequestBody SegueRequest req) {
        DjLounge lounge = lounge(loungeId);
        Role role = effectiveRole(user, loungeId);
        if (!Permission.OVERRIDE_QUEUE.allows(role.value())) {
            throw new ApiException(403, "forbidden", "Only a co-DJ or host can request an AI segue.");
        }
        Set<String> distinct = req.candidateTrackIds().stream().collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        double sentiment = req.currentSentimentScore() == null ? 0.5 : req.currentSentimentScore();
        List<String> recent = rooms.recentTrackIds(loungeId);
        var proposal = ai.propose(req.currentTrackId(), List.copyOf(distinct), recent, sentiment, 0.0,
                lounge.getBpmTolerancePct(), TransitionWriter.Verbosity.parse(lounge.getAiVerbosity()));
        if (proposal.isEmpty()) {
            throw new ApiException(422, "no_compatible_candidate",
                    "No candidate satisfies both the BPM (+/-" + lounge.getBpmTolerancePct()
                            + "%) and Camelot-key rules, or a track id is unknown.");
        }
        var p = proposal.get();
        return new SegueResponse(p.selectedTrackId(), p.matchReason(), p.transitionScript(), p.sourceEvents(),
                p.generatedAt());
    }

    /** Any participant: ask a question about the current (or a named) track. Answers cite the notes they used. */
    @PostMapping("/ask")
    public AskResponse ask(@AuthenticationPrincipal UserPrincipal user, @PathVariable String loungeId,
                           @Valid @RequestBody AskRequest req) {
        DjLounge lounge = lounge(loungeId);
        effectiveRole(user, loungeId); // membership check
        String question;
        try {
            question = Sanitizer.sanitize(req.question(), 300, "question");
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("question", e.getMessage());
        }
        String trackId = req.trackId() != null ? req.trackId() : rooms.nowPlayingTrackId(loungeId, lounge.getCurrentTrackId());
        Retriever.Answer a = ai.ask(question, trackId, rooms.queuedTrackIds(loungeId));
        rooms.logAiQuestion(loungeId, user.id(), a.grounded());
        return new AskResponse(a.text(), a.grounded(), a.citations(), Instant.now());
    }

    private DjLounge lounge(String loungeId) {
        return lounges.findById(loungeId)
                .orElseThrow(() -> new ApiException(404, "lounge_not_found", "No lounge with id " + loungeId + "."));
    }

    /** Global admin, else the caller's role inside this lounge. Non-members are refused. */
    private Role effectiveRole(UserPrincipal user, String loungeId) {
        if ("admin".equals(user.role())) {
            return Role.ADMIN;
        }
        LoungeParticipant p = participants.findByLoungeIdAndUserId(loungeId, user.id())
                .filter(m -> m.getLeftAt() == null)
                .orElseThrow(() -> new ApiException(403, "not_a_participant",
                        "Join this lounge with its invite code first."));
        return Role.fromValue(p.getLoungeRole()).orElse(Role.LISTENER);
    }
}
