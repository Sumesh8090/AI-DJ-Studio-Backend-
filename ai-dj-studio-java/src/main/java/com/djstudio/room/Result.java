package com.djstudio.room;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** What a room operation produced: events to deliver, effects to perform, and an optional AI request flag. */
public final class Result {

    private final List<OutEvent> events = new ArrayList<>();
    private final List<Effect> effects = new ArrayList<>();
    private boolean wantsSegueSuggestion;

    public List<OutEvent> events() {
        return events;
    }

    public List<Effect> effects() {
        return effects;
    }

    /** True when the current track entered its outro window and the AI DJ should propose the next segue. */
    public boolean wantsSegueSuggestion() {
        return wantsSegueSuggestion;
    }

    void requestSegue() {
        this.wantsSegueSuggestion = true;
    }

    void log(String type, String userId, Map<String, Object> payload) {
        effects.add(new Effect.LogEvent(type, userId, payload));
    }

    public boolean isEmpty() {
        return events.isEmpty() && effects.isEmpty() && !wantsSegueSuggestion;
    }

    /** First event with this name (handy in tests). */
    public OutEvent first(String name) {
        for (OutEvent e : events) {
            if (e.name().equals(name)) {
                return e;
            }
        }
        return null;
    }

    public List<OutEvent> all(String name) {
        List<OutEvent> out = new ArrayList<>();
        for (OutEvent e : events) {
            if (e.name().equals(name)) {
                out.add(e);
            }
        }
        return out;
    }

    public <T extends Effect> List<T> effectsOf(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Effect e : effects) {
            if (type.isInstance(e)) {
                out.add(type.cast(e));
            }
        }
        return out;
    }
}
