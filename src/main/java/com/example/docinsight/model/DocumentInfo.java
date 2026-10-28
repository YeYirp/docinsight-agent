package com.example.docinsight.model;

public record DocumentInfo(String id, String name, int pages, int chunks, long sizeBytes) {
}
