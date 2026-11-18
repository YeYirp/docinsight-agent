package com.example.docinsight.service;

import com.example.docinsight.config.VideoProperties;
import com.example.docinsight.model.Chunk;
import com.example.docinsight.model.DocumentInfo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 视频 -> 时间戳转写 -> 与文档相同的 Chunk 管线。 */
@Service
public class VideoIngestionService {

    private final VideoTranscriber transcriber;
    private final Chunker chunker;
    private final DocumentStore store;
    private final VideoProperties props;

    public VideoIngestionService(VideoTranscriber transcriber, Chunker chunker,
                                 DocumentStore store, VideoProperties props) {
        this.transcriber = transcriber;
        this.chunker = chunker;
        this.store = store;
        this.props = props;
    }

    public DocumentInfo ingestStored(StoredVideo video, String knowledgeBaseId) {
        if (knowledgeBaseId != null && store.getKnowledgeBase(knowledgeBaseId) == null) {
            throw new IllegalArgumentException("知识库不存在");
        }

        VideoTranscript transcript = transcriber.transcribe(video.path());
        double durationSeconds = transcript.durationSeconds();
        if (durationSeconds <= 0 && transcript.segments() != null
                && !transcript.segments().isEmpty()) {
            durationSeconds = transcript.segments().stream()
                    .mapToDouble(VideoTranscript.Segment::endSeconds)
                    .max()
                    .orElse(0);
        }
        long maxSeconds = Math.max(1, props.getMaxDurationMinutes()) * 60L;
        if (durationSeconds > maxSeconds) {
            throw new IllegalArgumentException("视频时长超过上限："
                    + props.getMaxDurationMinutes() + " 分钟");
        }
        if (transcript.segments() == null || transcript.segments().isEmpty()) {
            throw new IllegalArgumentException("视频没有识别到可用的语音内容");
        }

        List<DocumentParser.Block> blocks = new ArrayList<>();
        blocks.add(new DocumentParser.Block("视频语音转写", true, 1, formatTime(0)));
        for (VideoTranscript.Segment segment : transcript.segments()) {
            String text = segment.text() == null ? "" : segment.text().trim();
            if (text.isEmpty()) {
                continue;
            }
            blocks.add(new DocumentParser.Block(
                    text, false, 0, formatTime(segment.startSeconds())));
        }
        List<Chunk> chunks = chunker.chunk(video.docId(), video.name(), blocks);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("视频没有生成有效切片");
        }

        long durationMs = Math.round(durationSeconds * 1000);
        DocumentInfo info = new DocumentInfo(
                video.docId(),
                video.name(),
                knowledgeBaseId,
                1,
                chunks.size(),
                video.sizeBytes(),
                System.currentTimeMillis(),
                true,
                durationMs);
        store.save(info, chunks, video.mediaPath(), durationMs);
        return info;
    }

    private String formatTime(double seconds) {
        long total = Math.max(0, (long) Math.floor(seconds));
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long remaining = total % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%02d:%02d:%02d",
                    hours, minutes, remaining);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, remaining);
    }
}
