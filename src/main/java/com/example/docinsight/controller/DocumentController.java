package com.example.docinsight.controller;

import com.example.docinsight.model.Chunk;
import com.example.docinsight.model.DocumentInfo;
import com.example.docinsight.service.Chunker;
import com.example.docinsight.service.DocumentParser;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/docs")
public class DocumentController {

    private final DocumentParser parser;
    private final Chunker chunker;
    private final DocumentStore store;

    public DocumentController(DocumentParser parser, Chunker chunker, DocumentStore store) {
        this.parser = parser;
        this.chunker = chunker;
        this.store = store;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String name = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String lower = name.toLowerCase();
        if (!(lower.endsWith(".pdf") || lower.endsWith(".docx")
                || lower.endsWith(".md") || lower.endsWith(".txt"))) {
            throw new IllegalArgumentException("只支持 PDF / DOCX / MD / TXT");
        }

        byte[] bytes = file.getBytes();
        DocumentParser.Parsed parsed = parser.parse(name, bytes);
        String docId = UUID.randomUUID().toString().substring(0, 8);
        List<Chunk> chunks = chunker.chunk(docId, name, parsed.blocks());
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文档没有解析出有效内容");
        }

        DocumentInfo info = new DocumentInfo(docId, name, parsed.pages(), chunks.size(), bytes.length);
        store.save(info, chunks);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docId", docId);
        body.put("name", name);
        body.put("pages", parsed.pages());
        body.put("chunks", chunks.size());
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
