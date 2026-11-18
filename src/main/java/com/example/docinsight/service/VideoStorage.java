package com.example.docinsight.service;

import com.example.docinsight.config.VideoProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 视频原文件与普通文档共用知识库 sourcefiles 目录。 */
@Component
public class VideoStorage {

    private final VideoProperties props;
    private final SourceFileStorage sourceFiles;
    private final Path legacyRoot;

    public VideoStorage(VideoProperties props, SourceFileStorage sourceFiles,
                        @Value("${app.video.legacy-storage-dir:./data/media}")
                        String legacyRoot) {
        this.props = props;
        this.sourceFiles = sourceFiles;
        this.legacyRoot = Path.of(legacyRoot).toAbsolutePath().normalize();
    }

    public StoredVideo store(MultipartFile file, String docId, String name,
                             String knowledgeBaseDirectory) throws IOException {
        String mediaPath = sourceFiles.store(file, name, knowledgeBaseDirectory);
        return new StoredVideo(docId, name, sourceFiles.path(mediaPath),
                mediaPath, Files.size(sourceFiles.path(mediaPath)));
    }

    public StoredVideo store(MultipartFile file, String docId, String name) throws IOException {
        return store(file, docId, name, null);
    }

    public Path path(String mediaPath) {
        Path current = sourceFiles.path(mediaPath);
        if (Files.isRegularFile(current)) {
            return current;
        }
        if (mediaPath != null && !mediaPath.contains("/")) {
            Path legacy = legacyRoot.resolve(mediaPath).normalize();
            if (legacy.startsWith(legacyRoot) && Files.isRegularFile(legacy)) {
                return legacy;
            }
        }
        return current;
    }

    public void delete(String mediaPath) {
        if (mediaPath == null || mediaPath.isBlank()) {
            return;
        }
        sourceFiles.delete(mediaPath);
        if (!mediaPath.contains("/")) {
            try {
                Files.deleteIfExists(legacyRoot.resolve(mediaPath).normalize());
            } catch (IOException ignored) {
                // Cleanup is best effort.
            }
        }
    }

    public long maxFileSizeBytes() {
        return Math.max(1, props.getMaxFileSizeMb()) * 1024L * 1024L;
    }
}
