package com.example.docinsight.service;

import com.example.docinsight.model.Chunk;
import com.example.docinsight.model.DocumentInfo;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentIngestionService {

    private final DocumentParser parser;
    private final Chunker chunker;
    private final DocumentStore store;

    public DocumentIngestionService(DocumentParser parser, Chunker chunker, DocumentStore store) {
        this.parser = parser;
        this.chunker = chunker;
        this.store = store;
    }

    public DocumentInfo ingest(MultipartFile file, String knowledgeBaseId) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文档");
        }
        String name = cleanName(file.getOriginalFilename());
        String lower = name.toLowerCase();
        if (!(lower.endsWith(".pdf") || lower.endsWith(".docx")
                || lower.endsWith(".md") || lower.endsWith(".txt"))) {
            throw new IllegalArgumentException("只支持 PDF / DOCX / MD / TXT");
        }
        if (knowledgeBaseId != null && store.getKnowledgeBase(knowledgeBaseId) == null) {
            throw new IllegalArgumentException("知识库不存在");
        }

        byte[] bytes = file.getBytes();
        DocumentParser.Parsed parsed = parser.parse(name, bytes);
        String docId = UUID.randomUUID().toString().substring(0, 8);
        List<Chunk> chunks = chunker.chunk(docId, name, parsed.blocks());
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文档没有解析出有效内容");
        }

        DocumentInfo info = new DocumentInfo(
                docId, name, knowledgeBaseId, parsed.pages(), chunks.size(), bytes.length);
        store.save(info, chunks);
        return info;
    }

    private String cleanName(String originalName) {
        String name = originalName == null || originalName.isBlank() ? "unknown" : originalName;
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return separator >= 0 ? name.substring(separator + 1) : name;
    }
}
