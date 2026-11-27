package com.example.docinsight.service;

import com.example.docinsight.config.AiProperties;
import com.example.docinsight.model.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {

    private final AiProperties props = new AiProperties(
            new AiProperties.Ai("http://localhost", "", "test", 30, 3),
            new AiProperties.Embedding("http://localhost", "", "bge", 3, 32),
            new AiProperties.Rag(2, 200, 40));

    @Test
    void fallsBackToKeywordCandidatesWhenEmbeddingDisabled() {
        DocumentStore store = mock(DocumentStore.class);
        Chunk backup = new Chunk(
                "d1#0", "d1", "manual.md", "备份策略", "每日执行全量备份", "第 1 行", null);
        Chunk account = new Chunk(
                "d1#1", "d1", "manual.md", "账号锁定", "连续五次密码错误锁定账号", "第 9 行", null);
        when(store.hasChunks(null)).thenReturn(true);
        when(store.keywordCandidates(anySet(), isNull(), eq(20))).thenReturn(List.of(
                new DocumentStore.KeywordCandidate(backup, 1.0, 1.0),
                new DocumentStore.KeywordCandidate(account, 0.1, 0.0)));

        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.enabled()).thenReturn(false);

        RetrievalService service = new RetrievalService(store, embedding, props);
        List<RetrievalService.Hit> hits = service.search("备份多久一次", Set.of("备份"), null, 2);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).chunk().heading()).contains("备份");
        assertThat(hits.get(0).semanticUsed()).isFalse();
    }

    @Test
    void vectorCandidateIsRetrievedThroughStoreAndFused() {
        DocumentStore store = mock(DocumentStore.class);
        Chunk backup = new Chunk(
                "d1#0", "d1", "manual.md", "备份策略", "每日执行全量备份", "第 1 行", null);
        when(store.hasChunks(null)).thenReturn(true);
        when(store.chunksWithoutVectors(null, 32)).thenReturn(List.of());
        when(store.keywordCandidates(anySet(), isNull(), eq(20))).thenReturn(List.of());
        when(store.vectorCandidates(any(double[].class), isNull(), eq(20)))
                .thenReturn(List.of(new DocumentStore.VectorCandidate(backup, 0.1)));

        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.enabled()).thenReturn(true);
        when(embedding.embed(List.of("备份多久一次")))
                .thenReturn(List.of(new double[]{1.0, 0.0, 0.0}));

        RetrievalService service = new RetrievalService(store, embedding, props);
        List<RetrievalService.Hit> hits = service.search(
                "备份多久一次", Set.of("备份"), null, 2);

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.chunk().id()).isEqualTo("d1#0");
            assertThat(hit.semanticUsed()).isTrue();
            assertThat(hit.score()).isEqualTo(RetrievalService.rrfScore(1));
        });
    }

    @Test
    void rrfFusionDependsOnlyOnRankNotOnRawScoreScale() {
        // 两路各自的第一名 + 第二名，无论原始分是多少，融合后同一 chunk 名次相同。
        DocumentStore store = mock(DocumentStore.class);
        Chunk top = new Chunk("d1#0", "d1", "a.md", "标题", "正文", "第 1 行", null);
        Chunk second = new Chunk("d1#1", "d1", "a.md", "标题", "正文2", "第 2 行", null);
        when(store.hasChunks(null)).thenReturn(true);
        when(store.chunksWithoutVectors(null, 32)).thenReturn(List.of());
        when(store.keywordCandidates(anySet(), isNull(), eq(20))).thenReturn(List.of(
                new DocumentStore.KeywordCandidate(top, 0.01, 0.0),
                new DocumentStore.KeywordCandidate(second, 0.02, 0.0)));
        when(store.vectorCandidates(any(double[].class), isNull(), eq(20)))
                .thenReturn(List.of(new DocumentStore.VectorCandidate(second, 0.9)));
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.enabled()).thenReturn(true);
        when(embedding.embed(any())).thenReturn(List.of(new double[]{1.0, 0.0, 0.0}));

        List<RetrievalService.Hit> hits = new RetrievalService(store, embedding, props)
                .search("任意问题", Set.of("问题"), null, 2);
        // second 同时是向量第一、关键词第二，应排在最前；top 只有关键词第一。
        assertThat(hits.get(0).chunk().id()).isEqualTo("d1#1");
        assertThat(hits.get(1).chunk().id()).isEqualTo("d1#0");
    }

    @Test
    void embeddingTextIncludesHeadingForSymmetricRecall() {
        Chunk chunk = new Chunk("d1#0", "d1", "a.md", "备份策略", "每日全量备份", "第 1 行", null);
        assertThat(RetrievalService.embeddingText(chunk))
                .isEqualTo("备份策略\n每日全量备份");
    }

    @Test
    void scopedSearchOnlyReturnsSqlCandidatesFromSelectedDocuments() {
        DocumentStore store = mock(DocumentStore.class);
        Chunk ops = new Chunk(
                "d1#0", "d1", "ops.md", "备份策略", "每日执行全量备份", "第 1 行", null);
        Chunk account = new Chunk(
                "d2#0", "d2", "account.md", "账号锁定", "账号锁定三十分钟", "第 2 行", null);
        when(store.hasChunks(Set.of("d1"))).thenReturn(true);
        when(store.hasChunks(Set.of("d1", "d2"))).thenReturn(true);
        when(store.keywordCandidates(anySet(), eq(Set.of("d1")), eq(20)))
                .thenReturn(List.of(new DocumentStore.KeywordCandidate(ops, 1.0, 1.0)));
        when(store.keywordCandidates(anySet(), eq(Set.of("d1", "d2")), eq(20)))
                .thenReturn(List.of(
                        new DocumentStore.KeywordCandidate(ops, 1.0, 1.0),
                        new DocumentStore.KeywordCandidate(account, 1.0, 1.0)));

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
