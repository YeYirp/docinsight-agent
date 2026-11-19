package com.example.docinsight.service;

import com.example.docinsight.model.Chunk;
import com.example.docinsight.model.DocumentContentView;
import com.example.docinsight.model.DocumentInfo;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

@Service
public class DocumentEditService {

    private final DocumentStore store;
    private final DocumentSourceStorage sourceStorage;
    private final DocumentParser parser;
    private final Chunker chunker;
    private final DocumentViewService viewService;

    public DocumentEditService(DocumentStore store, DocumentSourceStorage sourceStorage,
                               DocumentParser parser, Chunker chunker,
                               DocumentViewService viewService) {
        this.store = store;
        this.sourceStorage = sourceStorage;
        this.parser = parser;
        this.chunker = chunker;
        this.viewService = viewService;
    }

    public DocumentContentView updateText(String docId, String text) throws IOException {
        DocumentInfo document = store.document(docId);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在");
        }
        DocumentFormat format = DocumentFormat.fromFilename(document.name())
                .orElse(DocumentFormat.TEXT);
        if (format != DocumentFormat.MARKDOWN && format != DocumentFormat.TEXT) {
            throw new IllegalArgumentException("该文件不支持编辑");
        }
        String sourcePath = store.sourcePath(docId);
        if (sourcePath == null) {
            throw new IllegalStateException("该文档没有保存原文件");
        }
        Path path = sourceStorage.path(sourcePath);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("原文件不存在");
        }

        String previous = Files.readString(path, StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(path.getParent(), ".docinsight-edit-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);

            DocumentParser.Parsed parsed;
            try (InputStream input = Files.newInputStream(path)) {
                parsed = parser.parse(document.name(), input);
            }
            List<Chunk> chunks = chunker.chunk(docId, document.name(), parsed.blocks());
            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("文件内容没有生成有效切片");
            }
            store.replaceContent(docId, chunks,
                    Files.size(path), parsed.pages());
        } catch (IOException | RuntimeException error) {
            try {
                Files.writeString(path, previous, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // Preserve the original edit error.
            }
            throw error;
        } finally {
            Files.deleteIfExists(temporary);
        }
        // 索引已经提交，此时再取视图失败不应该把文件回滚成旧内容。
        return viewService.view(docId);
    }
}
