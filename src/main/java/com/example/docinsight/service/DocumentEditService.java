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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DocumentEditService {

    private final DocumentStore store;
    private final DocumentSourceStorage sourceStorage;
    private final DocumentParser parser;
    private final Chunker chunker;
    private final DocumentViewService viewService;
    /** 同一文档的编辑串行化，避免两个请求互相覆盖对方的修改。 */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

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
        Object lock = locks.computeIfAbsent(docId, key -> new Object());
        synchronized (lock) {
            return doUpdate(docId, text);
        }
    }

    private DocumentContentView doUpdate(String docId, String text) throws IOException {
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

        // 1. 先在临时文件里写新内容并解析、切块。任何一步失败都不碰原文件。
        Path temporary = Files.createTempFile(path.getParent(), ".docinsight-edit-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            DocumentParser.Parsed parsed;
            try (InputStream input = Files.newInputStream(temporary)) {
                parsed = parser.parse(document.name(), input);
            }
            List<Chunk> chunks = chunker.chunk(docId, document.name(), parsed.blocks());
            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("文件内容没有生成有效切片");
            }
            long size = Files.size(temporary);

            // 2. 先提交数据库，成功后再原子替换文件。文件替换失败时把数据库改回旧内容，
            //    不会出现"文件已改、数据库没改"的永久不一致。
            store.replaceContent(docId, chunks, size, parsed.pages());
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fileError) {
                // 数据库已改但文件未改：用旧文件重新解析一遍，恢复索引。
                try {
                    DocumentParser.Parsed oldParsed;
                    try (InputStream input = Files.newInputStream(path)) {
                        oldParsed = parser.parse(document.name(), input);
                    }
                    List<Chunk> oldChunks = chunker.chunk(docId, document.name(), oldParsed.blocks());
                    if (!oldChunks.isEmpty()) {
                        store.replaceContent(docId, oldChunks, Files.size(path), oldParsed.pages());
                    }
                } catch (Exception rollbackError) {
                    throw new IOException("文件替换失败且索引回滚失败，请检查原文件", fileError);
                }
                throw fileError;
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return viewService.view(docId);
    }
}
