package com.example.docinsight.service;

import com.example.docinsight.config.AiProperties;
import com.example.docinsight.model.Chunk;
import com.example.docinsight.model.DocumentInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {

    private final AiProperties props = new AiProperties(
            new AiProperties.Ai("http://localhost", "", "test", 30, 3),
            new AiProperties.Embedding("http://localhost", "", "bge"),
            new AiProperties.Rag(2, 200, 40));

    @Test
    void fallsBackToKeywordWhenEmbeddingDisabled() {
        DocumentStore store = new DocumentStore();
        store.save(new DocumentInfo("d1", "manual.md", 1, 2, 100),
                List.of(
                        new Chunk("d1#0", "d1", "manual.md", "备份策略", "每日执行全量备份", "第 1 行", null),
                        new Chunk("d1#1", "d1", "manual.md", "账号锁定", "连续五次密码错误锁定账号", "第 9 行", null)
                ));

        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.enabled()).thenReturn(false);

        RetrievalService service = new RetrievalService(store, embedding, props);
        List<RetrievalService.Hit> hits = service.search("备份多久一次", Set.of("备份"), null, 2);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).chunk().heading()).contains("备份");
        assertThat(hits.get(0).semanticUsed()).isFalse();
    }

    @Test
    void keywordScoreCountsMatches() {
        // 每个关键词出现一次记 1 分，上限 2 分，再除以 (关键词数 × 2)
        assertThat(RetrievalService.keywordScore(Set.of("备份", "账号"), "备份与账号")).isEqualTo(0.5);
        assertThat(RetrievalService.keywordScore(Set.of("备份", "账号"), "只有备份")).isEqualTo(0.25);
        assertThat(RetrievalService.keywordScore(Set.of("备份"), "备份备份备份")).isEqualTo(1.0);
        assertThat(RetrievalService.keywordScore(Set.of(), "任意")).isZero();
    }

    @Test
    void scopedSearchOnlyUsesSelectedDocuments() {
        DocumentStore store = new DocumentStore();
        String kb1 = store.createKnowledgeBase("运维", "运维资料").id();
        String kb2 = store.createKnowledgeBase("账号", "账号资料").id();
        store.save(new DocumentInfo("d1", "ops.md", kb1, 1, 1, 100),
                List.of(new Chunk("d1#0", "d1", "ops.md", "备份策略", "每日执行全量备份", "第 1 行", null)));
        store.save(new DocumentInfo("d2", "account.md", kb2, 1, 1, 100),
                List.of(new Chunk("d2#0", "d2", "account.md", "账号锁定", "账号锁定三十分钟", "第 2 行", null)));

        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.enabled()).thenReturn(false);
        RetrievalService service = new RetrievalService(store, embedding, props);

        List<RetrievalService.Hit> onlyOps = service.searchInDocuments(
                "备份和账号", Set.of("备份", "账号"), Set.of("d1"), 4);
        List<RetrievalService.Hit> both = service.searchInDocuments(
                "备份和账号", Set.of("备份", "账号"), Set.of("d1", "d2"), 4);

        assertThat(onlyOps).extracting(hit -> hit.chunk().docId()).containsOnly("d1");
        assertThat(both).extracting(hit -> hit.chunk().docId()).containsExactlyInAnyOrder("d1", "d2");
    }
}
