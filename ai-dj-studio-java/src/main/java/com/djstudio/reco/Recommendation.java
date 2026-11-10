package com.djstudio.reco;

/** A recommended track. cf and content are the two component scores in [0,1]; source says why it ranked. */
public record Recommendation(String trackId, double score, double cf, double content, String source) {
}
