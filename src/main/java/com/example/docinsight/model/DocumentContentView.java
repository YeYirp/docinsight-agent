package com.example.docinsight.model;

import java.util.List;

public record DocumentContentView(
        DocumentInfo document,
        String mode,
        boolean sourceAvailable,
        String sourceStatus,
        boolean editable,
        String contentUrl,
        String rawText,
        List<Section> sections
) {
    public record Section(String heading, String location, String text) {
    }
}
