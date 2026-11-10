package com.djstudio.ws;

import com.djstudio.config.AiProperties;
import com.djstudio.config.SoundclashProperties;
import com.djstudio.config.WsProperties;
import com.djstudio.core.Role;
import com.djstudio.domain.DjLounge;
import com.djstudio.domain.QueueEntry;
import com.djstudio.domain.Track;
import com.djstudio.domain.User;
import com.djstudio.repo.DjLoungeRepository;
import com.djstudio.repo.LoungeParticipantRepository;
import com.djstudio.repo.QueueEntryRepository;
import com.djstudio.repo.TrackRepository;
import com.djstudio.repo.UserRepository;
import com.djstudio.room.Room;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Holds the live rooms. A room is loaded from the database on first use and evicted after it has been idle. */
@Component
class RoomRegistry {

    private final Map<String, RoomRuntime> rooms = new ConcurrentHashMap<>();
    private final DjLoungeRepository lounges;
    private final LoungeParticipantRepository participants;
    private final QueueEntryRepository queue;
    private final TrackRepository tracks;
    private final UserRepository users;
    private final WsProperties ws;
    private final AiProperties ai;
    private final SoundclashProperties soundclash;
    private final ObjectMapper mapper;

    RoomRegistry(DjLoungeRepository lounges, LoungeParticipantRepository participants, QueueEntryRepository queue,
                 TrackRepository tracks, UserRepository users, WsProperties ws, AiProperties ai,
                 SoundclashProperties soundclash, ObjectMapper mapper) {
        this.lounges = lounges;
        this.participants = participants;
        this.queue = queue;
        this.tracks = tracks;
        this.users = users;
        this.ws = ws;
        this.ai = ai;
        this.soundclash = soundclash;
        this.mapper = mapper;
    }

    Optional<RoomRuntime> peek(String loungeId) {
        return Optional.ofNullable(rooms.get(loungeId));
    }

    Collection<RoomRuntime> all() {
        return List.copyOf(rooms.values());
    }

    void evict(RoomRuntime runtime) {
        rooms.remove(runtime.loungeId(), runtime);
    }

    /** @return the live room, loading it from the database if needed; empty if the lounge does not exist. */
    Optional<RoomRuntime> getOrLoad(String loungeId) {
        RoomRuntime existing = rooms.get(loungeId);
        if (existing != null) {
            return Optional.of(existing);
        }
        Optional<DjLounge> lounge = lounges.findById(loungeId);
        if (lounge.isEmpty()) {
            return Optional.empty();
        }
        RoomRuntime loaded = load(lounge.get());
        RoomRuntime raced = rooms.putIfAbsent(loungeId, loaded);
        if (raced != null) {
            loaded.closeAll(org.springframework.web.socket.CloseStatus.NORMAL); // lost the race: just stop its executor
            return Optional.of(raced);
        }
        return Optional.of(loaded);
    }

    private RoomRuntime load(DjLounge lounge) {
        Room.Config cfg = new Room.Config(lounge.getBpmTolerancePct(), ws.presenceGraceMs(),
                ai.outroWindowSeconds(), ai.autoApproveMs(), ai.enabled(), lounge.isSoundclashEnabled(),
                soundclash.windowMs());
        Room room = new Room(lounge.getId(), lounge.getHostUserId(), cfg);

        var active = participants.findByLoungeIdAndLeftAtIsNull(lounge.getId());
        Map<String, User> byId = users.findAllById(active.stream().map(p -> p.getUserId()).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        for (var p : active) {
            User u = byId.get(p.getUserId());
            if (u != null) {
                room.addMember(u.getId(), u.getDisplayName(), Role.fromValue(u.getRole()).orElse(Role.LISTENER),
                        Role.fromValue(p.getLoungeRole()).orElse(Role.LISTENER), p.isMuted());
            }
        }
        room.restoreStatus(lounge.getStatus(), lounge.isLocked());

        List<QueueEntry> entries = queue.findByLoungeIdOrderByPositionAsc(lounge.getId());
        Map<String, Track> trackById = tracks.findAllById(entries.stream().map(QueueEntry::getTrackId).toList())
                .stream().collect(Collectors.toMap(Track::getId, Function.identity()));
        for (QueueEntry e : entries) {
            Track t = trackById.get(e.getTrackId());
            if (t != null) {
                room.restoreQueue(t.getId(), t.getTitle(), t.getDurationSeconds(), e.getAddedByUserId(), e.getVoteCount());
            }
        }
        if (lounge.getCurrentTrackId() != null) {
            tracks.findById(lounge.getCurrentTrackId()).ifPresent(t -> room.restoreNowPlaying(
                    new Room.TrackRef(t.getId(), t.getTitle(), t.getDurationSeconds()), System.currentTimeMillis()));
        }
        RoomRuntime runtime = new RoomRuntime(lounge.getId(), room, mapper);
        runtime.setAiVerbosity(lounge.getAiVerbosity());
        return runtime;
    }
}
