package com.example.docinsight.service;

import com.example.docinsight.model.Chunk;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检索层评测：Hit Rate@K。
 *
 * <p>每题给出若干"期望关键词"，只要 TopK 里任意一块命中任一关键词就算命中。
 * 这是一个便宜、可复现的指标；生成层指标（忠实度）需要人工或模型打分，本仓库不内置。
 */
@Service
public class EvalService {

    public record Case(String question, List<String> expect, String docId) {
    }

    private final RetrievalService retrieval;
    private final DocumentStore store;
    private final ObjectMapper mapper;
    private final List<Case> cases;

    public EvalService(RetrievalService retrieval, DocumentStore store, ObjectMapper mapper) {
        this.retrieval = retrieval;
        this.store = store;
        this.mapper = mapper;
        this.cases = load();
    }

    private List<Case> load() {
        List<Case> list = new ArrayList<>();
        try (InputStream in = new ClassPathResource("eval/questions.json").getInputStream()) {
            JsonNode root = mapper.readTree(in);
            for (JsonNode node : root) {
                List<String> expect = new ArrayList<>();
                node.path("expect").forEach(item -> expect.add(item.asText()));
                list.add(new Case(node.path("question").asText(), expect, node.path("docId").asText("")));
            }
        } catch (Exception e) {
            // 没有评测集不影响主流程
        }
        return List.copyOf(list);
    }

    public Map<String, Object> run(int topK) {
        int k = Math.max(1, topK);
        List<Map<String, Object>> details = new ArrayList<>();
        int hit = 0;

        for (Case item : cases) {
            Set<String> keywords = new LinkedHashSet<>(item.expect());
            List<RetrievalService.Hit> hits = retrieval.search(
                    item.question(), keywords, item.docId().isBlank() ? null : item.docId(), k);

            // 收紧口径：同一块里必须命中全部期望关键词，才算召回成功
            boolean matched = hits.stream()
                    .map(hitItem -> hitItem.chunk().text() + " " + hitItem.chunk().heading())
                    .anyMatch(text -> item.expect().stream().allMatch(text::contains));
            if (matched) {
                hit++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("question", item.question());
            row.put("hit", matched);
            row.put("top", hits.stream().limit(k).map(h -> Map.of(
                    "doc", h.chunk().docName(),
                    "location", h.chunk().location(),
                    "heading", h.chunk().heading()
            )).toList());
            details.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cases", cases.size());
        result.put("topK", k);
        result.put("hits", hit);
        result.put("hitRate", cases.isEmpty() ? 0.0 : Math.round(hit * 1000.0 / cases.size()) / 1000.0);
        result.put("details", details);
        return result;
    }

    public int caseCount() {
        return cases.size();
    }
}
