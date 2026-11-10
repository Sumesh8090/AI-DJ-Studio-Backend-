package com.djstudio.web.dto;

import com.djstudio.analytics.SentimentTimeline;
import java.time.Instant;
import java.util.List;

public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    public record TopTrack(String trackId, String title, long votes) {
    }

    public record SoundclashStats(long battles, long withWinner, long totalVotes) {
    }

    /** Everything the dashboard shows for one lounge. */
    public record LoungeAnalytics(
            String loungeId, String status, Instant startedAt, Long durationSeconds, long peakConcurrent,
            long aiQuestions, long participants, long onlineNow, long tracksPlayed, long queueAdds,
            long queueVotes, long chatMessages, SentimentTimeline.Summary sentiment,
            List<SentimentTimeline.Bucket> sentimentTimeline, List<TopTrack> topVotedTracks,
            SoundclashStats soundclash, Instant generatedAt) {
    }

    public record Overview(long lounges, long users, long tracks, long interactions, long chatMessages,
                           double averageSentiment, Instant generatedAt) {
    }
}
