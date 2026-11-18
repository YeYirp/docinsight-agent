package com.example.docinsight.service;

import com.example.docinsight.config.AiProperties;
import com.example.docinsight.model.Chunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合检索：vec0 向量召回 + SQLite FTS5 关键词召回，最后按既有权重融合。
 * SQL 负责筛选和召回，不再把整库切片加载到 Java 内存逐条打分。
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    public record Hit(Chunk chunk, double score, boolean semanticUsed) {
    }

    private final DocumentStore store;
    private final EmbeddingClient embeddingClient;
    private final AiProperties props;

    public RetrievalService(DocumentStore store, EmbeddingClient embeddingClient, AiProperties props) {
        this.store = store;
        this.embeddingClient = embeddingClient;
        this.props = props;
    }

    public List<Hit> search(String question, Set<String> keywords, String docId, int topK) {
        Set<String> scopedDocIds = docId == null || docId.isBlank() ? null : Set.of(docId);
        return searchInDocuments(question, keywords, scopedDocIds, topK);
    }

    public List<Hit> searchInDocuments(String question, Set<String> keywords,
                                       Collection<String> docIds, int topK) {
        if (!store.hasChunks(docIds)) {
            return List.of();
        }

        int limit = Math.max(20, Math.max(1, topK) * 5);
        List<DocumentStore.VectorCandidate> vectorHits = List.of();
        if (embeddingClient.enabled()) {
            ensureVectors(docIds);
            double[] queryVector = embedOne(question);
            if (queryVector != null) {
                vectorHits = store.vectorCandidates(queryVector, docIds, limit);
            }
        }
        List<DocumentStore.KeywordCandidate> keywordHits =
                store.keywordCandidates(keywords, docIds, limit);

        boolean semanticUsed = !vectorHits.isEmpty();
        Map<String, Chunk> chunks = new HashMap<>();
        Map<String, Double> semanticScores = new HashMap<>();
        Map<String, Double> keywordScores = new HashMap<>();
        Map<String, Double> headingScores = new HashMap<>();

        for (DocumentStore.VectorCandidate hit : vectorHits) {
            Chunk chunk = hit.chunk();
            chunks.putIfAbsent(chunk.id(), chunk);
            double similarity = Math.max(0, Math.min(1, 1.0 - hit.distance()));
            semanticScores.merge(chunk.id(), similarity, Math::max);
        }
        for (DocumentStore.KeywordCandidate hit : keywordHits) {
            Chunk chunk = hit.chunk();
            chunks.putIfAbsent(chunk.id(), chunk);
            keywordScores.merge(chunk.id(), hit.keywordScore(), Math::max);
            headingScores.merge(chunk.id(), hit.headingScore(), Math::max);
        }

        List<Hit> hits = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks.values()) {
            double semantic = semanticScores.getOrDefault(chunk.id(), 0.0);
            double keyword = keywordScores.getOrDefault(chunk.id(), 0.0);
            double heading = headingScores.getOrDefault(chunk.id(), 0.0);
            double score = semanticUsed
                    ? semantic * 0.6 + keyword * 0.25 + heading * 0.15
                    : keyword * 0.7 + heading * 0.3;
            hits.add(new Hit(chunk, score, semanticUsed));
        }
        return hits.stream()
                .sorted(Comparator.comparingDouble(Hit::score).reversed()
                        .thenComparing(hit -> hit.chunk().id()))
                .limit(Math.max(1, topK))
                .toList();
    }

    /** 缺失向量从 SQL 查询出来并写回 chunk_vector；进程内不保留任何向量缓存。 */
    private void ensureVectors(Collection<String> docIds) {
        int batchSize = Math.max(1, props.embedding().batchSize());
        int indexed = 0;
        while (true) {
            List<Chunk> pending = store.chunksWithoutVectors(docIds, batchSize);
            if (pending.isEmpty()) {
                break;
            }
            List<double[]> vectors = embeddingClient.embed(
                    pending.stream().map(Chunk::text).toList());
            if (vectors == null || vectors.size() != pending.size()) {
                log.warn("vector_index_failed chunks={} fallback=keyword_only", pending.size());
                return;
            }
            List<Chunk> withVectors = new ArrayList<>(pending.size());
            for (int index = 0; index < pending.size(); index++) {
                withVectors.add(pending.get(index).withVector(vectors.get(index)));
            }
            store.updateVectors(withVectors);
            indexed += withVectors.size();
            if (pending.size() < batchSize) {
                break;
            }
        }
        if (indexed > 0) {
            log.info("vector_index_ready chunks={}", indexed);
        }
    }

    private double[] embedOne(String question) {
        List<double[]> vectors = embeddingClient.embed(List.of(question));
        return vectors == null || vectors.isEmpty() ? null : vectors.get(0);
    }
}
