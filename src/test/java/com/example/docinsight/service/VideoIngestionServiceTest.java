package com.example.docinsight.service;

import com.example.docinsight.config.AiProperties;
import com.example.docinsight.config.VideoProperties;
import com.example.docinsight.model.Chunk;
import com.example.docinsight.model.DocumentInfo;
import com.example.docinsight.model.KnowledgeBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoIngestionServiceTest {

    private final AiProperties aiProps = new AiProperties(
            new AiProperties.Ai("http://localhost", "", "test", 30, 3),
            new AiProperties.Embedding("http://localhost", "", "bge", 1024, 32),
            new AiProperties.Rag(4, 200, 40));

    @Test
    void convertsTranscriptToTimestampedChunksAndKeepsMediaReference() {
        DocumentStore store = mock(DocumentStore.class);
        when(store.getKnowledgeBase("kb1"))
                .thenReturn(new KnowledgeBase("kb1", "视频库", "", 1, 0, 0));
        VideoTranscriber transcriber = path -> new VideoTranscript(125.4, "zh", List.of(
                new VideoTranscript.Segment(0, 3, "这是视频开头的第一段说明。"),
                new VideoTranscript.Segment(61, 64, "一分钟以后讲解第二个操作步骤。")));
        VideoProperties props = new VideoProperties();
        props.setMaxDurationMinutes(120);
        VideoIngestionService service = new VideoIngestionService(
                transcriber, new Chunker(aiProps), store, props);
        StoredVideo video = new StoredVideo(
                "doc1", "演示视频.mp4", Path.of("media/doc1.mp4"), "doc1.mp4", 4096);

        DocumentInfo info = service.ingestStored(video, "kb1");

        assertThat(info.media()).isTrue();
        assertThat(info.durationMs()).isEqualTo(125_400);
        assertThat(info.chunks()).isPositive();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Chunk>> chunks = ArgumentCaptor.forClass(List.class);
        verify(store).save(eq(info), chunks.capture(), eq("doc1.mp4"), eq(125_400L));
        assertThat(chunks.getValue()).anyMatch(chunk ->
                chunk.heading().contains("视频语音转写")
                        && chunk.location().contains("01:01"));
    }

    @Test
    void rejectsVideoWithoutSpeech() {
        DocumentStore store = mock(DocumentStore.class);
        VideoTranscriber transcriber = path ->
                new VideoTranscript(30, "zh", List.of());
        VideoProperties props = new VideoProperties();
        VideoIngestionService service = new VideoIngestionService(
                transcriber, new Chunker(aiProps), store, props);
        StoredVideo video = new StoredVideo(
                "doc2", "静音.mp4", Path.of("media/doc2.mp4"), "doc2.mp4", 2048);

        assertThatThrownBy(() -> service.ingestStored(video, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有识别到");
        verify(store, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any(), anyList(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }
}
