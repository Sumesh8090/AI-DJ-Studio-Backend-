package com.djstudio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "dj_lounges")
public class DjLounge {

    @Id
    @Column(length = 32)
    private String id;

    @Column(name = "invite_code", nullable = false, unique = true, length = 10)
    private String inviteCode;

    @Column(name = "host_user_id", nullable = false, length = 32)
    private String hostUserId;

    @Column(name = "vibe_profile", nullable = false, length = 200)
    private String vibeProfile;

    @Column(name = "current_track_id", length = 40)
    private String currentTrackId;

    /** scheduled | live | ended */
    @Column(nullable = false, length = 12)
    private String status = "scheduled";

    @Column(name = "is_locked", nullable = false)
    private boolean locked;

    @Column(name = "max_participants", nullable = false)
    private int maxParticipants = 40;

    @Column(name = "bpm_tolerance_pct", nullable = false)
    private double bpmTolerancePct = 8.0;

    @Column(name = "soundclash_enabled", nullable = false)
    private boolean soundclashEnabled = true;

    @Column(name = "ai_verbosity", nullable = false, length = 10)
    private String aiVerbosity = "normal";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "ended_at")
    private Instant endedAt;

    protected DjLounge() {
    }

    public DjLounge(String id, String inviteCode, String hostUserId, String vibeProfile, String currentTrackId,
                    int maxParticipants, double bpmTolerancePct, boolean soundclashEnabled, String aiVerbosity) {
        this.id = id;
        this.inviteCode = inviteCode;
        this.hostUserId = hostUserId;
        this.vibeProfile = vibeProfile;
        this.currentTrackId = currentTrackId;
        this.maxParticipants = maxParticipants;
        this.bpmTolerancePct = bpmTolerancePct;
        this.soundclashEnabled = soundclashEnabled;
        this.aiVerbosity = aiVerbosity;
    }

    public String getId() { return id; }
    public String getInviteCode() { return inviteCode; }
    public String getHostUserId() { return hostUserId; }
    public void setHostUserId(String hostUserId) { this.hostUserId = hostUserId; }
    public String getVibeProfile() { return vibeProfile; }
    public String getCurrentTrackId() { return currentTrackId; }
    public void setCurrentTrackId(String currentTrackId) { this.currentTrackId = currentTrackId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }
    public int getMaxParticipants() { return maxParticipants; }
    public double getBpmTolerancePct() { return bpmTolerancePct; }
    public boolean isSoundclashEnabled() { return soundclashEnabled; }
    public String getAiVerbosity() { return aiVerbosity; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getEndedAt() { return endedAt; }
    public void markEnded() { this.status = "ended"; this.endedAt = Instant.now(); }
}
