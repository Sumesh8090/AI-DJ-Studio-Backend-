package com.djstudio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Membership plus per-lounge role (a user can host one lounge and listen in another). */
@Entity
@Table(name = "lounge_participants")
public class LoungeParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lounge_id", nullable = false, length = 32)
    private String loungeId;

    @Column(name = "user_id", nullable = false, length = 32)
    private String userId;

    /** listener | co_dj | host */
    @Column(name = "lounge_role", nullable = false, length = 10)
    private String loungeRole = "listener";

    @Column(name = "is_muted", nullable = false)
    private boolean muted;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt = Instant.now();

    @Column(name = "left_at")
    private Instant leftAt;

    protected LoungeParticipant() {
    }

    public LoungeParticipant(String loungeId, String userId, String loungeRole) {
        this.loungeId = loungeId;
        this.userId = userId;
        this.loungeRole = loungeRole;
    }

    public void rejoin() {
        this.leftAt = null;
        this.joinedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getLoungeId() { return loungeId; }
    public String getUserId() { return userId; }
    public String getLoungeRole() { return loungeRole; }
    public void setLoungeRole(String loungeRole) { this.loungeRole = loungeRole; }
    public boolean isMuted() { return muted; }
    public void setMuted(boolean muted) { this.muted = muted; }
    public Instant getJoinedAt() { return joinedAt; }
    public Instant getLeftAt() { return leftAt; }
    public void setLeftAt(Instant leftAt) { this.leftAt = leftAt; }
}
