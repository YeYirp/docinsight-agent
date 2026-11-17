package com.example.docinsight.model;

public record DocumentInfo(
        String id,
        String name,
        String knowledgeBaseId,
        int pages,
        int chunks,
        long sizeBytes,
        long createdAt
) {
    public DocumentInfo(String id, String name, String knowledgeBaseId,
                        int pages, int chunks, long sizeBytes) {
        this(id, name, knowledgeBaseId, pages, chunks, sizeBytes, System.currentTimeMillis());
    }

    public DocumentInfo(String id, String name, int pages, int chunks, long sizeBytes) {
        this(id, name, null, pages, chunks, sizeBytes, System.currentTimeMillis());
    }
}
