package com.example.docinsight.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 普通文档原件存储；兼容旧版本的 data/documents 目录。 */
@Component
public class DocumentSourceStorage {

    private final SourceFileStorage sourceFiles;
    private final Path legacyRoot;

    public DocumentSourceStorage(
            SourceFileStorage sourceFiles,
            @Value("${app.documents.legacy-storage-dir:./data/documents}")
            String legacyRoot) {
        this.sourceFiles = sourceFiles;
        this.legacyRoot = Path.of(legacyRoot).toAbsolutePath().normalize();
    }

    public String store(MultipartFile file, String docId, String name,
                        String knowledgeBaseDirectory) throws IOException {
        return sourceFiles.store(file, name, knowledgeBaseDirectory);
    }

    public Path path(String sourcePath) {
        Path current = sourceFiles.path(sourcePath);
        if (Files.isRegularFile(current)) {
            return current;
        }
        if (sourcePath != null && !sourcePath.contains("/")) {
            Path legacy = legacyRoot.resolve(sourcePath).normalize();
            if (legacy.startsWith(legacyRoot) && Files.isRegularFile(legacy)) {
                return legacy;
            }
        }
        return current;
    }

    public void delete(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return;
        }
        sourceFiles.delete(sourcePath);
        if (!sourcePath.contains("/")) {
            try {
                Files.deleteIfExists(legacyRoot.resolve(sourcePath).normalize());
            } catch (IOException ignored) {
                // Cleanup is best effort.
            }
        }
    }
}
