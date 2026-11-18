package com.example.docinsight.service;

import com.example.docinsight.config.VideoProperties;
import com.example.docinsight.model.DocumentInfo;
import com.example.docinsight.model.KnowledgeBase;
import com.example.docinsight.model.VideoIngestionJob;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 单线程视频队列，避免多个 Whisper 转写同时抢占 CPU。 */
@Service
public class VideoIngestionJobService {

    public static final String QUEUED = "QUEUED";
    public static final String PROCESSING = "PROCESSING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    private static final Logger log = LoggerFactory.getLogger(VideoIngestionJobService.class);

    private final VideoProperties props;
    private final VideoStorage storage;
    private final VideoIngestionService ingestion;
    private final DocumentStore store;
    private final Map<String, VideoIngestionJob> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger threadNumber = new AtomicInteger();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "video-ingest-" + threadNumber.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    public VideoIngestionJobService(VideoProperties props, VideoStorage storage,
                                    VideoIngestionService ingestion, DocumentStore store) {
        this.props = props;
        this.storage = storage;
        this.ingestion = ingestion;
        this.store = store;
    }

    public VideoIngestionJob submit(MultipartFile file, String knowledgeBaseId) throws IOException {
        if (!props.isEnabled()) {
            throw new IllegalArgumentException("视频摄取已关闭");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的视频");
        }
        String name = cleanName(file.getOriginalFilename());
        DocumentFormat format = DocumentFormat.fromFilename(name)
                .filter(DocumentFormat::isVideo)
                .orElseThrow(() -> new IllegalArgumentException("不支持的视频格式"));
        if (file.getSize() > storage.maxFileSizeBytes()) {
            throw new IllegalArgumentException("视频不能超过 "
                    + props.getMaxFileSizeMb() + "MB");
        }

        String docId = UUID.randomUUID().toString().substring(0, 8);
        KnowledgeBase knowledgeBase = knowledgeBaseId == null
                ? null : store.getKnowledgeBase(knowledgeBaseId);
        StoredVideo stored = storage.store(file, docId, name,
                knowledgeBase == null ? null : knowledgeBase.sourceDir());
        String jobId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        VideoIngestionJob job = new VideoIngestionJob(
                jobId, docId, name, knowledgeBaseId,
                QUEUED, "等待处理", "", now, now, null);
        jobs.put(jobId, job);
        cleanupOldJobs(now);

        executor.submit(() -> process(job, stored, format));
        return job;
    }

    public VideoIngestionJob get(String jobId) {
        VideoIngestionJob job = jobs.get(jobId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "视频任务不存在");
        }
        return job;
    }

    private void process(VideoIngestionJob initial, StoredVideo stored, DocumentFormat format) {
        update(initial, PROCESSING, "正在转写视频", "", null);
        try {
            DocumentInfo info = ingestion.ingestStored(stored, initial.knowledgeBaseId());
            update(initial, DONE, "已完成", "", info);
            log.info("视频摄取完成: {} ({})", initial.name(), info.chunks());
        } catch (Throwable error) {
            storage.delete(stored.mediaPath());
            String message = error.getMessage() == null ? error.getClass().getSimpleName()
                    : error.getMessage();
            update(initial, FAILED, "处理失败", message, null);
            log.warn("视频摄取失败: {} ({})", initial.name(), format.extension(), error);
        }
    }

    private void update(VideoIngestionJob base, String status, String stage,
                        String error, DocumentInfo document) {
        long now = System.currentTimeMillis();
        jobs.put(base.id(), new VideoIngestionJob(
                base.id(), base.docId(), base.name(), base.knowledgeBaseId(),
                status, stage, error, base.createdAt(), now, document));
    }

    private void cleanupOldJobs(long now) {
        jobs.entrySet().removeIf(entry -> {
            VideoIngestionJob job = entry.getValue();
            return (DONE.equals(job.status()) || FAILED.equals(job.status()))
                    && now - job.updatedAt() > 24L * 60 * 60 * 1000;
        });
    }

    private String cleanName(String originalName) {
        String name = originalName == null || originalName.isBlank() ? "video" : originalName;
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return separator >= 0 ? name.substring(separator + 1) : name;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
