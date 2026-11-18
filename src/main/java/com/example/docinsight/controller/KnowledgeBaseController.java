package com.example.docinsight.controller;

import com.example.docinsight.model.CreateKnowledgeBaseInput;
import com.example.docinsight.model.DocumentInfo;
import com.example.docinsight.model.KnowledgeBase;
import com.example.docinsight.model.UpdateKnowledgeBaseInput;
import com.example.docinsight.model.VideoIngestionJob;
import com.example.docinsight.service.DocumentFormat;
import com.example.docinsight.service.DocumentIngestionService;
import com.example.docinsight.service.DocumentSourceStorage;
import com.example.docinsight.service.DocumentStore;
import com.example.docinsight.service.SourceFileStorage;
import com.example.docinsight.service.VideoIngestionJobService;
import com.example.docinsight.service.VideoStorage;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final DocumentStore store;
    private final DocumentIngestionService ingestion;
    private final VideoIngestionJobService videoJobs;
    private final VideoStorage videoStorage;
    private final DocumentSourceStorage sourceStorage;
    private final SourceFileStorage sourceFiles;

    public KnowledgeBaseController(DocumentStore store, DocumentIngestionService ingestion,
                                   VideoIngestionJobService videoJobs, VideoStorage videoStorage,
                                   DocumentSourceStorage sourceStorage,
                                   SourceFileStorage sourceFiles) {
        this.store = store;
        this.ingestion = ingestion;
        this.videoJobs = videoJobs;
        this.videoStorage = videoStorage;
        this.sourceStorage = sourceStorage;
        this.sourceFiles = sourceFiles;
    }

    @GetMapping
    public List<KnowledgeBase> list() {
        return store.listKnowledgeBases();
    }

    @GetMapping("/{id}")
    public KnowledgeBase get(@PathVariable String id) {
        return requireKnowledgeBase(id);
    }

    @PostMapping
    public ResponseEntity<KnowledgeBase> create(@Valid @RequestBody CreateKnowledgeBaseInput input)
            throws IOException {
        String sourceDir = sourceFiles.createDirectory(input.name());
        KnowledgeBase created = store.createKnowledgeBase(
                input.name(), input.description(), sourceDir);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public KnowledgeBase update(@PathVariable String id,
                                @Valid @RequestBody UpdateKnowledgeBaseInput input)
            throws IOException {
        KnowledgeBase existing = requireKnowledgeBase(id);
        Path oldDirectory = sourceFiles.resolveDirectory(existing.sourceDir());
        String requested = input.sourcePath();
        Path requestedDirectory = requested == null || requested.isBlank()
                ? oldDirectory : sourceFiles.resolveDirectory(requested);
        if (requestedDirectory.equals(oldDirectory)
                && !existing.name().equals(input.name().trim())
                && sourceFiles.isDefaultDirectory(existing.sourceDir(), existing.name())) {
            requestedDirectory = sourceFiles.defaultDirectory(input.name());
        }
        if (Files.exists(requestedDirectory) && !Files.isDirectory(requestedDirectory)) {
            throw new IllegalArgumentException("原文件保存路径必须是文件夹");
        }
        List<String> storedPaths = new ArrayList<>();
        for (DocumentStore.DocumentPaths paths
                : store.documentPathsForKnowledgeBase(id)) {
            if (paths.sourcePath() != null) {
                storedPaths.add(paths.sourcePath());
            }
            if (paths.mediaPath() != null) {
                storedPaths.add(paths.mediaPath());
            }
        }
        java.util.Map<String, String> moves = sourceFiles.relocateFiles(
                storedPaths, requestedDirectory);
        try {
            return store.updateKnowledgeBaseWithPaths(
                    id, input.name(), input.description(),
                    sourceFiles.storageReference(requestedDirectory), moves);
        } catch (RuntimeException error) {
            sourceFiles.restoreFiles(moves);
            throw error;
        }
    }

    @GetMapping("/{id}/storage")
    public Map<String, Object> storage(@PathVariable String id) {
        KnowledgeBase item = requireKnowledgeBase(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", sourceFiles.absoluteDirectory(item.sourceDir()));
        body.put("directory", item.sourceDir());
        return body;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        KnowledgeBase existing = requireKnowledgeBase(id);
        List<String> mediaPaths = store.mediaPathsForKnowledgeBase(id);
        List<String> sourcePaths = store.sourcePathsForKnowledgeBase(id);
        store.removeKnowledgeBase(id);
        mediaPaths.forEach(videoStorage::delete);
        sourcePaths.forEach(sourceStorage::delete);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", id);
        body.put("documents", existing.documentCount());
        return body;
    }

    @GetMapping("/{id}/documents")
    public List<DocumentInfo> documents(@PathVariable String id) {
        requireKnowledgeBase(id);
        return store.documentsForKnowledgeBase(id);
    }

    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(@PathVariable String id,
                                                       @RequestParam("file") MultipartFile file)
            throws IOException {
        requireKnowledgeBase(id);
        if (DocumentFormat.fromFilename(file.getOriginalFilename())
                .filter(DocumentFormat::isVideo).isPresent()) {
            VideoIngestionJob job = videoJobs.submit(file, id);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("async", true);
            body.put("jobId", job.id());
            body.put("docId", job.docId());
            body.put("name", job.name());
            body.put("knowledgeBaseId", id);
            body.put("status", job.status());
            body.put("stage", job.stage());
            return ResponseEntity.accepted().body(body);
        }

        DocumentInfo info = ingestion.ingest(file, id);
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

    private KnowledgeBase requireKnowledgeBase(String id) {
        KnowledgeBase item = store.getKnowledgeBase(id);
        if (item == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在");
        }
        return item;
    }
}
