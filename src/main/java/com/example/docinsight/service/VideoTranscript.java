package com.example.docinsight.service;

import java.util.List;

public record VideoTranscript(
        double durationSeconds,
        String language,
        List<Segment> segments
) {
    public record Segment(double startSeconds, double endSeconds, String text) {
    }
}
