package com.djstudio.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tracks")
public class Track {

    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(nullable = false, length = 300)
    private String artist;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "audio_asset_url", length = 500)
    private String audioAssetUrl;

    @Column(nullable = false)
    private double bpm;

    @Column(name = "camelot_key", nullable = false, length = 3)
    private String camelotKey;

    @Column(nullable = false)
    private double energy;

    @Column(nullable = false)
    private double valence;

    /** Metadata timeline used for RAG grounding. Owned by this aggregate; replaced as a whole on update. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "track_id", nullable = false)
    @OrderBy("tsSeconds ASC, id ASC")
    private List<TrackTimelineEvent> timeline = new ArrayList<>();

    protected Track() {
    }

    public Track(String id, String title, String artist, int durationSeconds, String audioAssetUrl) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.durationSeconds = durationSeconds;
        this.audioAssetUrl = audioAssetUrl;
    }

    public void setAcoustic(double bpm, String camelotKey, double energy, double valence) {
        this.bpm = bpm;
        this.camelotKey = camelotKey;
        this.energy = energy;
        this.valence = valence;
    }

    public void replaceTimeline(List<TrackTimelineEvent> events) {
        timeline.clear();
        timeline.addAll(events);
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public int getDurationSeconds() { return durationSeconds; }
    public String getAudioAssetUrl() { return audioAssetUrl; }
    public double getBpm() { return bpm; }
    public String getCamelotKey() { return camelotKey; }
    public double getEnergy() { return energy; }
    public double getValence() { return valence; }
    public List<TrackTimelineEvent> getTimeline() { return timeline; }
}
