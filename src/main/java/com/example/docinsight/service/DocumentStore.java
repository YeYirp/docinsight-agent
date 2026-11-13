package com.example.docinsight.service;

import com.example.docinsight.model.Chunk;
import com.example.docinsight.model.DocumentInfo;
import com.example.docinsight.model.KnowledgeBase;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 内存知识库与文档索引：单机演示够用，换持久化存储时保留这层接口即可。 */
@Service
public class DocumentStore {

    private final Map<String, KnowledgeBase> knowledgeBases = new ConcurrentHashMap<>();
    private final Map<String, DocumentInfo> documents = new ConcurrentHashMap<>();
    private final Map<String, List<Chunk>> chunksByDoc = new ConcurrentHashMap<>();

    public KnowledgeBase createKnowledgeBase(String name, String description) {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isBlank()) {
            throw new IllegalArgumentException("知识库名称不能为空");
        }
        boolean duplicate = knowledgeBases.values().stream()
                .anyMatch(item -> item.name().equalsIgnoreCase(normalizedName));
        if (duplicate) {
            throw new IllegalArgumentException("同名知识库已存在");
        }
        KnowledgeBase created = new KnowledgeBase(
                UUID.randomUUID().toString(),
                normalizedName,
                description == null ? "" : description.trim(),
                System.currentTimeMillis(),
                0,
                0
        );
        knowledgeBases.put(created.id(), created);
        return enrich(created);
    }

    public List<KnowledgeBase> listKnowledgeBases() {
        return knowledgeBases.values().stream()
                .sorted(Comparator.comparingLong(KnowledgeBase::createdAt))
                .map(this::enrich)
                .toList();
    }

    public KnowledgeBase getKnowledgeBase(String id) {
        KnowledgeBase item = knowledgeBases.get(id);
        return item == null ? null : enrich(item);
    }

    public boolean removeKnowledgeBase(String id) {
        if (knowledgeBases.remove(id) == null) {
            return false;
        }
        List<String> documentIds = documents.values().stream()
                .filter(document -> id.equals(document.knowledgeBaseId()))
                .map(DocumentInfo::id)
                .toList();
        documentIds.forEach(this::remove);
        return true;
    }

    public List<DocumentInfo> documentsForKnowledgeBase(String knowledgeBaseId) {
        return documents.values().stream()
                .filter(document -> knowledgeBaseId.equals(document.knowledgeBaseId()))
                .sorted(Comparator.comparing(DocumentInfo::name))
                .toList();
    }

    public Set<String> documentIdsForKnowledgeBases(Collection<String> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return Set.of();
        }
        Set<String> selected = new HashSet<>(knowledgeBaseIds);
        Set<String> result = new HashSet<>();
        for (DocumentInfo document : documents.values()) {
            if (selected.contains(document.knowledgeBaseId())) {
                result.add(document.id());
            }
        }
        return result;
    }

    public void save(DocumentInfo info, List<Chunk> chunks) {
        if (info.knowledgeBaseId() != null && !knowledgeBases.containsKey(info.knowledgeBaseId())) {
            throw new IllegalArgumentException("知识库不存在");
        }
        documents.put(info.id(), info);
        chunksByDoc.put(info.id(), List.copyOf(chunks));
    }

    public List<DocumentInfo> list() {
        return documents.values().stream()
                .sorted(Comparator.comparing(DocumentInfo::id))
                .toList();
    }

    public List<Chunk> chunks(String docId) {
        if (docId == null || docId.isBlank()) {
            List<Chunk> all = new ArrayList<>();
            chunksByDoc.values().forEach(all::addAll);
            return all;
        }
        return chunksByDoc.getOrDefault(docId, List.of());
    }

    public List<Chunk> chunksForDocuments(Collection<String> docIds) {
        if (docIds == null) {
            return chunks((String) null);
        }
        List<Chunk> selected = new ArrayList<>();
        for (String docId : docIds) {
            selected.addAll(chunksByDoc.getOrDefault(docId, List.of()));
        }
        return selected;
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
        grouped.forEach((docId, updates) -> {
            Map<String, Chunk> byId = new LinkedHashMap<>();
            for (Chunk existing : chunksByDoc.getOrDefault(docId, List.of())) {
                byId.put(existing.id(), existing);
            }
            for (Chunk update : updates) {
                byId.put(update.id(), update);
            }
            chunksByDoc.put(docId, List.copyOf(byId.values()));
        });
    }

    private KnowledgeBase enrich(KnowledgeBase item) {
        List<DocumentInfo> scoped = documentsForKnowledgeBase(item.id());
        int documentCount = scoped.size();
        int chunkCount = scoped.stream().mapToInt(DocumentInfo::chunks).sum();
        return new KnowledgeBase(
                item.id(),
                item.name(),
                item.description(),
                item.createdAt(),
                documentCount,
                chunkCount
        );
    }
}
