package com.example.docinsight.model;

public record DocumentInfo(
        String id,
        String name,
        String knowledgeBaseId,
        int pages,
        int chunks,
        long sizeBytes,
        long createdAt,
        boolean media,
        long durationMs
) {
    public DocumentInfo(String id, String name, String knowledgeBaseId,
                        int pages, int chunks, long sizeBytes, long createdAt) {
        this(id, name, knowledgeBaseId, pages, chunks, sizeBytes, createdAt, false, 0);
    }

    public DocumentInfo(String id, String name, String knowledgeBaseId,
                        int pages, int chunks, long sizeBytes) {
        this(id, name, knowledgeBaseId, pages, chunks, sizeBytes,
                System.currentTimeMillis(), false, 0);
    }

    public DocumentInfo(String id, String name, int pages, int chunks, long sizeBytes) {
        this(id, name, null, pages, chunks, sizeBytes,
                System.currentTimeMillis(), false, 0);
    }
}
