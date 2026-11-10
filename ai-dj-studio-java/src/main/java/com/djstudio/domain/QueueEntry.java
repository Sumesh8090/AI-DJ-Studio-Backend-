package com.djstudio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One slot in a lounge's Next Up queue. position is sparse (steps of 1000) so a reorder touches one row. */
@Entity
@Table(name = "queue_entries")
public class QueueEntry {

    public static final int POSITION_STEP = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lounge_id", nullable = false, length = 32)
    private String loungeId;

    @Column(name = "track_id", nullable = false, length = 40)
    private String trackId;

    @Column(nullable = false)
    private int position;

    @Column(name = "vote_count", nullable = false)
    private int voteCount;

    @Column(name = "added_by_user_id", length = 32)
    private String addedByUserId;

    protected QueueEntry() {
    }

    public QueueEntry(String loungeId, String trackId, int position, String addedByUserId) {
        this.loungeId = loungeId;
        this.trackId = trackId;
        this.position = position;
        this.addedByUserId = addedByUserId;
    }

    public QueueEntry(String loungeId, String trackId, int position, int voteCount, String addedByUserId) {
        this(loungeId, trackId, position, addedByUserId);
        this.voteCount = voteCount;
    }

    public Long getId() { return id; }
    public String getLoungeId() { return loungeId; }
    public String getTrackId() { return trackId; }
    public int getPosition() { return position; }
    public int getVoteCount() { return voteCount; }
    public String getAddedByUserId() { return addedByUserId; }
}
