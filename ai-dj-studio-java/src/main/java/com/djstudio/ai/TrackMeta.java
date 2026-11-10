package com.djstudio.ai;

import java.util.List;

/** Everything the AI is allowed to know about a track: acoustic features plus its curated timeline entries. */
public record TrackMeta(String trackId, String title, double bpm, String camelotKey, double energy,
                        List<MetaEntry> entries) {

    public TrackMeta {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
