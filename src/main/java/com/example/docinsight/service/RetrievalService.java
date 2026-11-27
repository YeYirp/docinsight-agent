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

        // RRF（倒数排名融合）：语义分与关键词分处在不同量纲，直接加权会让
        // 任一通道的分数分布变化就改变整体排序。RRF 只看名次，稳定得多。
        final int rrfConstant = 60;
        Map<String, Chunk> chunks = new HashMap<>();
        Map<String, Double> rrf = new HashMap<>();
        Map<String, Boolean> semanticFlags = new HashMap<>();

        for (int rank = 0; rank < vectorHits.size(); rank++) {
            Chunk chunk = vectorHits.get(rank).chunk();
            chunks.putIfAbsent(chunk.id(), chunk);
            rrf.merge(chunk.id(), 1.0 / (rrfConstant + rank + 1), Double::sum);
            semanticFlags.merge(chunk.id(), true, (a, b) -> a || b);
        }
        for (int rank = 0; rank < keywordHits.size(); rank++) {
            Chunk chunk = keywordHits.get(rank).chunk();
            chunks.putIfAbsent(chunk.id(), chunk);
            rrf.merge(chunk.id(), 1.0 / (rrfConstant + rank + 1), Double::sum);
        }

        List<Hit> hits = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks.values()) {
            double score = rrf.getOrDefault(chunk.id(), 0.0);
            hits.add(new Hit(chunk, score,
                    Boolean.TRUE.equals(semanticFlags.get(chunk.id()))));
        }
        return hits.stream()
                .sorted(Comparator.comparingDouble(Hit::score).reversed()
                        .thenComparing(hit -> hit.chunk().id()))
                .limit(Math.max(1, topK))
                .toList();
    }

    /** 单元测试可见：RRF 分数只依赖名次，不受两路分数量纲影响。 */
    static double rrfScore(int rank) {
        return 1.0 / (60.0 + rank);
    }

    /** 缺失向量从 SQL 查询出来并写回 chunk_vector；进程内不保留任何向量缓存。 */
    private void ensureVectors(Collection<String> docIds) {
        int batchSize = Math.max(1, props.embedding().batchSize());
        int indexed = 0;
        int maxBatches = Math.max(1, 100_000 / batchSize);
        for (int batchCount = 0; batchCount < maxBatches; batchCount++) {
            List<Chunk> pending = store.chunksWithoutVectors(docIds, batchSize);
            if (pending.isEmpty()) {
                break;
            }
            // 标题与正文一起编码：用户按章节标题提问时向量侧也能命中。
            List<double[]> vectors = embeddingClient.embed(
                    pending.stream().map(RetrievalService::embeddingText).toList());
            if (vectors == null || vectors.size() != pending.size()) {
                log.warn("vector_index_failed chunks={} fallback=keyword_only", pending.size());
                return;
            }
            List<Chunk> withVectors = new ArrayList<>(pending.size());
            for (int index = 0; index < pending.size(); index++) {
                withVectors.add(pending.get(index).withVector(vectors.get(index)));
            }
            int before = store.countVectors();
            store.updateVectors(withVectors);
            int after = store.countVectors();
            indexed += withVectors.size();
            // 并发删除可能让本批全部写不进去；此时继续循环只会无限重试同一个批次。
            if (after <= before) {
                log.warn("vector_index_no_progress chunks={} fallback=keyword_only", pending.size());
                return;
            }
            if (pending.size() < batchSize) {
                break;
            }
        }
        if (indexed > 0) {
            log.info("vector_index_ready chunks={}", indexed);
        }
    }

    /** 标题 + 正文，与 FTS 的 search_terms 保持一致，让两路召回能力对称。 */
    static String embeddingText(Chunk chunk) {
        String heading = chunk.heading() == null ? "" : chunk.heading().trim();
        return heading.isEmpty() ? chunk.text() : heading + "\n" + chunk.text();
    }

    private double[] embedOne(String question) {
        List<double[]> vectors = embeddingClient.embed(List.of(question));
        return vectors == null || vectors.isEmpty() ? null : vectors.get(0);
    }
}
