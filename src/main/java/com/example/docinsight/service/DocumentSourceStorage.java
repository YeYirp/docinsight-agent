package com.example.docinsight.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 文档原件保存在磁盘，数据库只保存文件名。 */
@Component
public class DocumentSourceStorage {

    private final Path root;

    public DocumentSourceStorage(
            @Value("${app.documents.storage-dir:./data/documents}") String storageDir) {
        this.root = Path.of(storageDir).toAbsolutePath().normalize();
    }

    public String store(MultipartFile file, String docId, String name) throws IOException {
        DocumentFormat format = DocumentFormat.fromFilename(name).orElseThrow();
        Files.createDirectories(root);
        String sourceName = docId + format.extension();
        Path target = path(sourceName);
        try (InputStream input = file.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return sourceName;
    }

    public Path path(String sourceName) {
        Path target = root.resolve(sourceName).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法的文档路径");
        }
        return target;
    }

    public void delete(String sourceName) {
        if (sourceName == null || sourceName.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(path(sourceName));
        } catch (IOException ignored) {
            // Database cleanup should not fail because of an orphan file.
        }
    }
}
