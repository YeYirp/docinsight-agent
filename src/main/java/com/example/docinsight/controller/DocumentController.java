package com.example.docinsight.controller;

import com.example.docinsight.model.DocumentInfo;
import com.example.docinsight.service.DocumentIngestionService;
import com.example.docinsight.service.DocumentStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/docs")
public class DocumentController {

    private final DocumentIngestionService ingestion;
    private final DocumentStore store;

    public DocumentController(DocumentIngestionService ingestion, DocumentStore store) {
        this.ingestion = ingestion;
        this.store = store;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        DocumentInfo info = ingestion.ingest(file, null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docId", info.id());
        body.put("name", info.name());
        body.put("knowledgeBaseId", info.knowledgeBaseId());
        body.put("pages", info.pages());
        body.put("chunks", info.chunks());
        return body;
    }

    @GetMapping
    public List<DocumentInfo> list() {
        return store.list();
    }

    @DeleteMapping("/{docId}")
    public Map<String, Object> delete(@PathVariable String docId) {
        store.remove(docId);
        return Map.of("deleted", docId);
    }
}
