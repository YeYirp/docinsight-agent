package com.example.docinsight.controller;

import com.example.docinsight.model.CreateKnowledgeBaseInput;
import com.example.docinsight.model.DocumentInfo;
import com.example.docinsight.model.KnowledgeBase;
import com.example.docinsight.service.DocumentIngestionService;
import com.example.docinsight.service.DocumentStore;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final DocumentStore store;
    private final DocumentIngestionService ingestion;

    public KnowledgeBaseController(DocumentStore store, DocumentIngestionService ingestion) {
        this.store = store;
        this.ingestion = ingestion;
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
    public ResponseEntity<KnowledgeBase> create(@Valid @RequestBody CreateKnowledgeBaseInput input) {
        KnowledgeBase created = store.createKnowledgeBase(input.name(), input.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        KnowledgeBase existing = requireKnowledgeBase(id);
        store.removeKnowledgeBase(id);
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
    public Map<String, Object> upload(@PathVariable String id,
                                      @RequestParam("file") MultipartFile file) throws IOException {
        requireKnowledgeBase(id);
        DocumentInfo info = ingestion.ingest(file, id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docId", info.id());
        body.put("name", info.name());
        body.put("knowledgeBaseId", info.knowledgeBaseId());
        body.put("pages", info.pages());
        body.put("chunks", info.chunks());
        return body;
    }

    private KnowledgeBase requireKnowledgeBase(String id) {
        KnowledgeBase item = store.getKnowledgeBase(id);
        if (item == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在");
        }
        return item;
    }
}
