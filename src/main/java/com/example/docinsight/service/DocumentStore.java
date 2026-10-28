package com.example.docinsight.service;

import com.example.docinsight.model.Chunk;
import com.example.docinsight.model.DocumentInfo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 内存文档库：单机演示够用，换向量库时只改 RetrievalService 的实现。 */
@Service
public class DocumentStore {

    private final Map<String, DocumentInfo> documents = new ConcurrentHashMap<>();
    private final Map<String, List<Chunk>> chunksByDoc = new ConcurrentHashMap<>();

    public void save(DocumentInfo info, List<Chunk> chunks) {
        documents.put(info.id(), info);
        chunksByDoc.put(info.id(), List.copyOf(chunks));
    }

    public List<DocumentInfo> list() {
        return new ArrayList<>(documents.values());
    }

    public List<Chunk> chunks(String docId) {
        if (docId == null || docId.isBlank()) {
            List<Chunk> all = new ArrayList<>();
            chunksByDoc.values().forEach(all::addAll);
            return all;
        }
        return chunksByDoc.getOrDefault(docId, List.of());
    }

    public void remove(String docId) {
        documents.remove(docId);
        chunksByDoc.remove(docId);
    }

    public int totalChunks() {
        return chunksByDoc.values().stream().mapToInt(List::size).sum();
    }

    /** 用带向量的副本替换原块，避免每次检索重复向量化。 */
    public void updateVectors(List<Chunk> withVectors) {
        Map<String, List<Chunk>> grouped = new LinkedHashMap<>();
        for (Chunk chunk : withVectors) {
            grouped.computeIfAbsent(chunk.docId(), key -> new ArrayList<>()).add(chunk);
        }
        grouped.forEach((docId, list) -> chunksByDoc.put(docId, List.copyOf(list)));
    }
}
