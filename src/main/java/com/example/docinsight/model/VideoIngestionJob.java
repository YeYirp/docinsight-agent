package com.example.docinsight.model;

public record VideoIngestionJob(
        String id,
        String docId,
        String name,
        String knowledgeBaseId,
        String status,
        String stage,
        String error,
        long createdAt,
        long updatedAt,
        DocumentInfo document
) {
}
