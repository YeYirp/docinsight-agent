package com.example.docinsight.service;

import com.example.docinsight.config.AiProperties;
import com.example.docinsight.model.Chunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 混合检索：向量相似度 0.6 + 关键词 0.25 + 标题命中 0.15。
 * 向量不可用时自动退化为 关键词 0.7 + 标题 0.3。
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    public record Hit(Chunk chunk, double score, boolean semanticUsed) {
    }

    private final DocumentStore store;
    private final EmbeddingClient embeddingClient;
    private final AiProperties props;

    private volatile boolean vectorReady;
    private volatile boolean vectorTried;

    public RetrievalService(DocumentStore store, EmbeddingClient embeddingClient, AiProperties props) {
        this.store = store;
        this.embeddingClient = embeddingClient;
        this.props = props;
    }

    public List<Hit> search(String question, Set<String> keywords, String docId, int topK) {
        List<Chunk> chunks = store.chunks(docId);
        if (chunks.isEmpty()) {
            return List.of();
        }
        ensureVectors(chunks);
        chunks = store.chunks(docId);

        double[] queryVector = vectorReady ? embedOne(question) : null;
        boolean semanticUsed = queryVector != null;

        List<Hit> hits = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            double semantic = semanticUsed ? cosine(queryVector, chunk.vector()) : 0;
            double keyword = keywordScore(keywords, chunk.text());
            double heading = headingScore(keywords, chunk.heading());
            double score = semanticUsed
                    ? semantic * 0.6 + keyword * 0.25 + heading * 0.15
                    : keyword * 0.7 + heading * 0.3;
            hits.add(new Hit(chunk, score, semanticUsed));
        }
        return hits.stream()
                .sorted(Comparator.comparingDouble(Hit::score).reversed())
                .limit(Math.max(1, topK))
                .toList();
    }

    /** 只向量化一次；失败后不再重试，避免每个请求都打一次外部接口。 */
    private void ensureVectors(List<Chunk> chunks) {
        if (vectorTried || !embeddingClient.enabled()) {
            return;
        }
        synchronized (this) {
            if (vectorTried) {
                return;
            }
            vectorTried = true;
            List<double[]> vectors = embeddingClient.embed(
                    chunks.stream().map(Chunk::text).toList());
            if (vectors != null) {
                List<Chunk> withVectors = new ArrayList<>(chunks.size());
                for (int i = 0; i < chunks.size(); i++) {
                    withVectors.add(chunks.get(i).withVector(vectors.get(i)));
                }
                store.updateVectors(withVectors);
                vectorReady = true;
                log.info("vector_index_ready chunks={}", withVectors.size());
            } else {
                log.warn("vector_index_failed fallback=keyword_only");
            }
        }
    }

    private double[] embedOne(String question) {
        List<double[]> vectors = embeddingClient.embed(List.of(question));
        return vectors == null || vectors.isEmpty() ? null : vectors.get(0);
    }

    static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return (na == 0 || nb == 0) ? 0 : Math.max(0, dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }

    static double keywordScore(Set<String> keywords, String text) {
        if (keywords == null || keywords.isEmpty() || text == null) {
            return 0;
        }
        String lower = text.toLowerCase();
        // 按出现次数加权：命中 1 次记 1，2 次及以上记 2，避免"提了一句"和"整节都在讲"同分
        double hit = 0;
        for (String keyword : keywords) {
            String k = keyword.toLowerCase();
            if (k.isBlank()) {
                continue;
            }
            int count = 0;
            int from = 0;
            while (count < 2) {
                int index = lower.indexOf(k, from);
                if (index < 0) {
                    break;
                }
                count++;
                from = index + k.length();
            }
            hit += count;
        }
        return Math.min(1.0, hit / (keywords.size() * 2.0));
    }

    private static double headingScore(Set<String> keywords, String heading) {
        if (keywords == null || heading == null || heading.isBlank()) {
            return 0;
        }
        String lower = heading.toLowerCase();
        return keywords.stream().anyMatch(k -> lower.contains(k.toLowerCase())) ? 1.0 : 0.0;
    }
}
