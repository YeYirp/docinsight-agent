package com.example.docinsight.service;

import java.nio.file.Path;

public record StoredVideo(
        String docId,
        String name,
        Path path,
        String mediaPath,
        long sizeBytes
) {
}
