package com.example.docinsight.service;

import com.example.docinsight.config.VideoProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 视频原文件保存在磁盘，数据库只记录文件名。 */
@Component
public class VideoStorage {

    private final VideoProperties props;
    private final Path root;

    public VideoStorage(VideoProperties props) {
        this.props = props;
        this.root = Path.of(props.getStorageDir()).toAbsolutePath().normalize();
    }

    public StoredVideo store(MultipartFile file, String docId, String name) throws IOException {
        DocumentFormat format = DocumentFormat.fromFilename(name)
                .filter(DocumentFormat::isVideo)
                .orElseThrow(() -> new IllegalArgumentException("不是支持的视频格式"));
        Files.createDirectories(root);
        String mediaPath = docId + format.extension();
        Path target = path(mediaPath);
        try (InputStream input = file.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return new StoredVideo(docId, name, target, mediaPath, Files.size(target));
    }

    public Path path(String mediaPath) {
        Path target = root.resolve(mediaPath).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法的视频路径");
        }
        return target;
    }

    public void delete(String mediaPath) {
        if (mediaPath == null || mediaPath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(path(mediaPath));
        } catch (IOException ignored) {
            // Orphan cleanup should not break database deletion.
        }
    }

    public long maxFileSizeBytes() {
        return Math.max(1, props.getMaxFileSizeMb()) * 1024L * 1024L;
    }
}
