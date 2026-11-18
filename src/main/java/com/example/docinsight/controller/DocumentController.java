package com.example.docinsight.controller;

import com.example.docinsight.model.DocumentInfo;
import com.example.docinsight.model.DocumentContentView;
import com.example.docinsight.model.VideoIngestionJob;
import com.example.docinsight.service.DocumentFormat;
import com.example.docinsight.service.DocumentIngestionService;
import com.example.docinsight.service.DocumentSourceStorage;
import com.example.docinsight.service.DocumentStore;
import com.example.docinsight.service.DocumentViewService;
import com.example.docinsight.service.VideoIngestionJobService;
import com.example.docinsight.service.VideoStorage;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/docs")
public class DocumentController {

    private final DocumentIngestionService ingestion;
    private final DocumentStore store;
    private final VideoIngestionJobService videoJobs;
    private final VideoStorage videoStorage;
    private final DocumentSourceStorage sourceStorage;
    private final DocumentViewService viewService;

    public DocumentController(DocumentIngestionService ingestion, DocumentStore store,
                              VideoIngestionJobService videoJobs, VideoStorage videoStorage,
                              DocumentSourceStorage sourceStorage,
                              DocumentViewService viewService) {
        this.ingestion = ingestion;
        this.store = store;
        this.videoJobs = videoJobs;
        this.videoStorage = videoStorage;
        this.sourceStorage = sourceStorage;
        this.viewService = viewService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file)
            throws IOException {
        if (DocumentFormat.fromFilename(file.getOriginalFilename())
                .filter(DocumentFormat::isVideo).isPresent()) {
            VideoIngestionJob job = videoJobs.submit(file, null);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("async", true);
            body.put("jobId", job.id());
            body.put("docId", job.docId());
            body.put("name", job.name());
            body.put("status", job.status());
            body.put("stage", job.stage());
            return ResponseEntity.accepted().body(body);
        }

        DocumentInfo info = ingestion.ingest(file, null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("async", false);
        body.put("docId", info.id());
        body.put("name", info.name());
        body.put("knowledgeBaseId", info.knowledgeBaseId());
        body.put("pages", info.pages());
        body.put("chunks", info.chunks());
        body.put("media", info.media());
        body.put("durationMs", info.durationMs());
        return ResponseEntity.ok(body);
    }

    @GetMapping
    public List<DocumentInfo> list() {
        return store.list();
    }

    @DeleteMapping("/{docId}")
    public Map<String, Object> delete(@PathVariable String docId) {
        String mediaPath = store.mediaPath(docId);
        String sourcePath = store.sourcePath(docId);
        store.remove(docId);
        videoStorage.delete(mediaPath);
        sourceStorage.delete(sourcePath);
        return Map.of("deleted", docId);
    }

    @GetMapping("/{docId}/content")
    public DocumentContentView content(@PathVariable String docId) {
        DocumentContentView content = viewService.view(docId);
        if (content == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "文档不存在");
        }
        return content;
    }

    @GetMapping("/{docId}/source")
    public ResponseEntity<?> source(@PathVariable String docId,
                                    @RequestHeader(value = HttpHeaders.RANGE, required = false)
                                    String rangeHeader) throws IOException {
        String sourcePath = store.sourcePath(docId);
        if (sourcePath == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "该文档没有保存原文件");
        }
        Path path = sourceStorage.path(sourcePath);
        if (!Files.isRegularFile(path)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "原文件不存在");
        }
        return fileResponse(path, rangeHeader);
    }

    @GetMapping("/{docId}/media")
    public ResponseEntity<?> media(@PathVariable String docId,
                                   @RequestHeader(value = HttpHeaders.RANGE, required = false)
                                   String rangeHeader) throws IOException {
        String mediaPath = store.mediaPath(docId);
        if (mediaPath == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "该文档没有视频文件");
        }
        Path path = videoStorage.path(mediaPath);
        if (!Files.isRegularFile(path)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "视频文件不存在");
        }

        return fileResponse(path, rangeHeader);
    }

    private ResponseEntity<?> fileResponse(Path path, String rangeHeader) throws IOException {
        Resource resource = new FileSystemResource(path);
        long length = resource.contentLength();
        MediaType mediaType = MediaTypeFactory.getMediaType(path.getFileName().toString())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(length)
                    .body(resource);
        }

        List<HttpRange> ranges;
        try {
            ranges = HttpRange.parseRanges(rangeHeader);
        } catch (IllegalArgumentException error) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + length)
                    .build();
        }
        if (ranges.size() != 1) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + length)
                    .build();
        }
        long start;
        long end;
        try {
            start = ranges.get(0).getRangeStart(length);
            end = ranges.get(0).getRangeEnd(length);
        } catch (IllegalArgumentException error) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + length)
                    .build();
        }
        long count = end - start + 1;
        InputStream input = Files.newInputStream(path);
        try {
            input.skipNBytes(start);
        } catch (IOException error) {
            input.close();
            throw error;
        }
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(mediaType)
                .contentLength(count)
                .header(HttpHeaders.CONTENT_RANGE,
                        "bytes " + start + "-" + end + "/" + length)
                .body(new InputStreamResource(new LimitedInputStream(input, count)));
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;

        private LimitedInputStream(InputStream input, long remaining) {
            super(input);
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = super.read(buffer, offset,
                    (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }
}
