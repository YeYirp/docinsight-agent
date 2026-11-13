package com.example.docinsight.model;

public record DocumentInfo(
        String id,
        String name,
        String knowledgeBaseId,
        int pages,
        int chunks,
        long sizeBytes
) {
    public DocumentInfo(String id, String name, int pages, int chunks, long sizeBytes) {
        this(id, name, null, pages, chunks, sizeBytes);
    }
}
