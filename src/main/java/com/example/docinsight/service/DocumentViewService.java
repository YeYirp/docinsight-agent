package com.example.docinsight.service;

import com.example.docinsight.model.Chunk;
import com.example.docinsight.model.DocumentContentView;
import com.example.docinsight.model.DocumentInfo;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class DocumentViewService {

    private static final long MAX_INLINE_TEXT_BYTES = 2L * 1024 * 1024;

    private final DocumentStore store;
    private final DocumentSourceStorage sourceStorage;

    public DocumentViewService(DocumentStore store, DocumentSourceStorage sourceStorage) {
        this.store = store;
        this.sourceStorage = sourceStorage;
    }

    public DocumentContentView view(String docId) {
        DocumentInfo document = store.document(docId);
        if (document == null) {
            return null;
        }
        DocumentFormat format = DocumentFormat.fromFilename(document.name())
                .orElse(DocumentFormat.TEXT);
        String sourcePath = store.sourcePath(docId);
        Path sourceFile = sourcePath == null ? null : sourceStorage.path(sourcePath);
        boolean sourceAvailable = sourceFile != null && Files.isRegularFile(sourceFile);
        String sourceStatus = sourcePath == null
                ? "OLD_VERSION" : (sourceAvailable ? "AVAILABLE" : "MISSING");
        boolean editable = sourceAvailable
                && (format == DocumentFormat.MARKDOWN || format == DocumentFormat.TEXT);
        List<DocumentContentView.Section> sections = store.chunks(docId).stream()
                .map(this::section)
                .toList();

        if (format.isVideo()) {
            return new DocumentContentView(document, "video", true, "AVAILABLE", false,
                    "/api/docs/" + docId + "/media", null, sections);
        }
        if (format == DocumentFormat.PDF && sourceAvailable) {
            return new DocumentContentView(document, "pdf", true, sourceStatus, false,
                    "/api/docs/" + docId + "/source", null, sections);
        }
        if ((format == DocumentFormat.MARKDOWN || format == DocumentFormat.TEXT)
                && sourceAvailable) {
            String raw = readInlineText(sourceFile);
            if (raw != null) {
                return new DocumentContentView(document, "text", true, sourceStatus, editable,
                        "/api/docs/" + docId + "/source", raw, sections);
            }
        }
        return new DocumentContentView(document, "sections", sourceAvailable, sourceStatus, false,
                sourceAvailable ? "/api/docs/" + docId + "/source" : null,
                null, sections);
    }

    private DocumentContentView.Section section(Chunk chunk) {
        return new DocumentContentView.Section(
                chunk.heading(), chunk.location(), chunk.text());
    }

    private String readInlineText(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_INLINE_TEXT_BYTES) {
                return null;
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            return null;
        }
    }
}
