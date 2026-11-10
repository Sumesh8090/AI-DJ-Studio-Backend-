package com.djstudio.ai;

/** One curated fact about a track (sample credit, trivia, glossary term...). The only source the AI may quote. */
public record MetaEntry(String id, String type, String text, Integer timestampSeconds) {

    public MetaEntry {
        if (id == null || id.isBlank() || text == null || text.isBlank()) {
            throw new IllegalArgumentException("A metadata entry needs an id and text.");
        }
        type = type == null ? "trivia" : type;
    }

    /** Stable citation id used in sourceEvents. */
    public String citation() {
        return "evt_" + id;
    }
}
