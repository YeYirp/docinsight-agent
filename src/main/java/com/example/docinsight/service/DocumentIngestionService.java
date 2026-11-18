package com.example.docinsight.service;

import com.example.docinsight.model.Chunk;
import com.example.docinsight.model.DocumentInfo;
import com.example.docinsight.model.KnowledgeBase;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentIngestionService {

    private final DocumentParser parser;
    private final Chunker chunker;
    private final DocumentStore store;
    private final DocumentSourceStorage sourceStorage;

    public DocumentIngestionService(DocumentParser parser, Chunker chunker,
                                    DocumentStore store, DocumentSourceStorage sourceStorage) {
        this.parser = parser;
        this.chunker = chunker;
        this.store = store;
        this.sourceStorage = sourceStorage;
    }

    public DocumentInfo ingest(MultipartFile file, String knowledgeBaseId) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文档");
        }
        String name = cleanName(file.getOriginalFilename());
        if (!DocumentFormat.supports(name)) {
            throw new IllegalArgumentException("只支持 " + DocumentFormat.supportedLabels());
        }
        DocumentFormat format = DocumentFormat.fromFilename(name).orElseThrow();
        if (format.isVideo()) {
            throw new IllegalArgumentException("视频文件需要走视频摄取流程");
        }
        if (file.getSize() > 50L * 1024 * 1024) {
            throw new IllegalArgumentException("文档单文件不能超过 50MB");
        }
        KnowledgeBase knowledgeBase = knowledgeBaseId == null
                ? null : store.getKnowledgeBase(knowledgeBaseId);
        if (knowledgeBaseId != null && knowledgeBase == null) {
            throw new IllegalArgumentException("知识库不存在");
        }

        String docId = UUID.randomUUID().toString().substring(0, 8);
        String sourcePath = sourceStorage.store(file, docId, name,
                knowledgeBase == null ? null : knowledgeBase.sourceDir());
        try {
            DocumentParser.Parsed parsed;
            try (InputStream input = Files.newInputStream(sourceStorage.path(sourcePath))) {
                parsed = parser.parse(name, input);
            }
            List<Chunk> chunks = chunker.chunk(docId, name, parsed.blocks());
            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("文档没有解析出有效内容");
            }

            DocumentInfo info = new DocumentInfo(
                    docId, name, knowledgeBaseId, parsed.pages(), chunks.size(), file.getSize(),
                    System.currentTimeMillis());
            store.save(info, chunks, sourcePath, null, 0);
            return info;
        } catch (IOException | RuntimeException error) {
            sourceStorage.delete(sourcePath);
            throw error;
        }
    }

    private String cleanName(String originalName) {
        String name = originalName == null || originalName.isBlank() ? "unknown" : originalName;
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return separator >= 0 ? name.substring(separator + 1) : name;
    }
}
