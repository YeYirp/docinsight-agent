package com.example.docinsight.model;

public record KnowledgeBase(
        String id,
        String name,
        String description,
        long createdAt,
        long updatedAt,
        int documentCount,
        int chunkCount
) {
    public KnowledgeBase(String id, String name, String description,
                         long createdAt, int documentCount, int chunkCount) {
        this(id, name, description, createdAt, createdAt, documentCount, chunkCount);
    }
}
