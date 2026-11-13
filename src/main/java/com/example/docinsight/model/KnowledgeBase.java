package com.example.docinsight.model;

public record KnowledgeBase(
        String id,
        String name,
        String description,
        long createdAt,
        int documentCount,
        int chunkCount
) {
}
