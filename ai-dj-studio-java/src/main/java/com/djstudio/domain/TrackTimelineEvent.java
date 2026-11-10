package com.djstudio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One metadata entry (sample credit, trivia, glossary term) the AI DJ may cite when answering. */
@Entity
@Table(name = "track_timeline_events")
public class TrackTimelineEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ts_seconds", nullable = false)
    private int tsSeconds;

    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;

    @Column(name = "text", nullable = false, length = 1000)
    private String text;

    protected TrackTimelineEvent() {
    }

    public TrackTimelineEvent(int tsSeconds, String eventType, String text) {
        this.tsSeconds = tsSeconds;
        this.eventType = eventType;
        this.text = text;
    }

    public Long getId() { return id; }
    public int getTsSeconds() { return tsSeconds; }
    public String getEventType() { return eventType; }
    public String getText() { return text; }
}
