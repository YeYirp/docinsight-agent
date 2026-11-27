package com.example.docinsight.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 每个知识库一个原文件目录：data/sourcefiles/{知识库名字}/。
 * 数据库只保存相对于 sourcefiles 根目录的路径。
 */
@Component
public class SourceFileStorage {

    private static final String GLOBAL_DIRECTORY = "未归属知识库";

    private final Path root;
    private final boolean allowOutsideRoot;

    public SourceFileStorage(
            @Value("${app.storage.sourcefiles-dir:./data/sourcefiles}") String rootDirectory,
            @Value("${app.storage.allow-outside-root:false}") boolean allowOutsideRoot) {
        this.root = Path.of(rootDirectory).toAbsolutePath().normalize();
        this.allowOutsideRoot = allowOutsideRoot;
    }

    private boolean withinRoot(Path target) {
        return allowOutsideRoot || (target.startsWith(root) && !target.equals(root));
    }

    public boolean allowsOutsideRoot() {
        return allowOutsideRoot;
    }

    public String rootPath() {
        return root.toString();
    }

    public String absoluteDirectory(String directory) {
        return path(directory).toString();
    }

    public Path resolveDirectory(String directory) {
        String value = directory == null || directory.isBlank()
                ? GLOBAL_DIRECTORY : directory;
        Path raw = Path.of(value);
        Path target = (raw.isAbsolute() ? raw : root.resolve(raw)).normalize();
        if (!withinRoot(target)) {
            throw new IllegalArgumentException("目录必须位于原文件根目录内");
        }
        return target;
    }

    public String storageReference(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        if (withinRoot(normalized) && !normalized.equals(root) && normalized.startsWith(root)) {
            return root.relativize(normalized).toString();
        }
        if (!withinRoot(normalized)) {
            throw new IllegalArgumentException("目录必须位于原文件根目录内");
        }
        return normalized.toString();
    }

    public boolean isDefaultDirectory(String reference, String knowledgeBaseName) {
        Path actual = resolveDirectory(reference);
        Path expected = root.resolve(safeDirectoryName(knowledgeBaseName)).normalize();
        return actual.equals(expected);
    }

    public Path defaultDirectory(String knowledgeBaseName) {
        return root.resolve(safeDirectoryName(knowledgeBaseName)).normalize();
    }

    public String createDirectory(String knowledgeBaseName) throws IOException {
        Files.createDirectories(root);
        String base = safeDirectoryName(knowledgeBaseName);
        String candidate = base;
        int counter = 2;
        while (Files.exists(root.resolve(candidate))) {
            candidate = base + " (" + counter++ + ")";
        }
        Files.createDirectories(root.resolve(candidate));
        return candidate;
    }

    public String renameDirectory(String oldDirectory, String newKnowledgeBaseName)
            throws IOException {
        Files.createDirectories(root);
        String base = safeDirectoryName(newKnowledgeBaseName);
        if (base.equals(oldDirectory)) {
            Files.createDirectories(root.resolve(base));
            return base;
        }
        String candidate = base;
        int counter = 2;
        while (Files.exists(root.resolve(candidate))) {
            candidate = base + " (" + counter++ + ")";
        }
        Path source = path(oldDirectory);
        if (Files.exists(source)) {
            Files.move(source, path(candidate));
        } else {
            Files.createDirectories(path(candidate));
        }
        return candidate;
    }

    public String store(MultipartFile file, String originalName, String directory)
            throws IOException {
        Path targetDirectory = resolveDirectory(directory);
        Files.createDirectories(targetDirectory);
        String filename = safeFileName(originalName);
        String candidate = filename;
        String stem = stripExtension(filename);
        String extension = extensionOf(filename);
        int counter = 2;
        while (Files.exists(targetDirectory.resolve(candidate))) {
            candidate = stem + " (" + counter++ + ")" + extension;
        }
        try (InputStream input = file.getInputStream()) {
            Files.copy(input, targetDirectory.resolve(candidate),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        return targetDirectory.resolve(candidate).toString();
    }

    public Path path(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return root;
        }
        Path raw = Path.of(relativePath);
        Path target = (raw.isAbsolute() ? raw : root.resolve(raw)).normalize();
        if (!withinRoot(target)) {
            throw new IllegalArgumentException("非法的原文件路径");
        }
        return target;
    }

    public Map<String, String> relocateFiles(List<String> storedPaths, Path targetDirectory)
            throws IOException {
        Files.createDirectories(targetDirectory);
        Map<String, String> moved = new LinkedHashMap<>();
        for (String storedPath : storedPaths) {
            if (storedPath == null || storedPath.isBlank()) {
                continue;
            }
            Path source = path(storedPath);
            if (!Files.isRegularFile(source)) {
                continue;
            }
            Path target = uniqueTarget(targetDirectory, source.getFileName().toString());
            if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
                continue;
            }
            try {
                Files.move(source, target);
            } catch (IOException moveError) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(source);
            }
            moved.put(storedPath, target.toString());
            removeEmptyParent(source);
        }
        return moved;
    }

    public void restoreFiles(Map<String, String> moved) {
        moved.forEach((oldStoredPath, newPath) -> {
            try {
                Path oldPath = path(oldStoredPath);
                Path current = Path.of(newPath).toAbsolutePath().normalize();
                if (Files.isRegularFile(current)) {
                    Files.createDirectories(oldPath.getParent());
                    Files.move(current, oldPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException error) {
                org.slf4j.LoggerFactory.getLogger(SourceFileStorage.class)
                        .warn("文件回滚失败 path={} error={}", newPath, error.getMessage());
            }
        });
    }

    private Path uniqueTarget(Path directory, String filename) {
        String stem = stripExtension(filename);
        String extension = extensionOf(filename);
        String candidate = filename;
        int counter = 2;
        while (Files.exists(directory.resolve(candidate))) {
            candidate = stem + " (" + counter++ + ")" + extension;
        }
        return directory.resolve(candidate);
    }

    private void removeEmptyParent(Path file) {
        Path parent = file.getParent();
        if (parent == null || parent.equals(root) || !parent.startsWith(root)
                || !Files.isDirectory(parent)) {
            return;
        }
        try (var entries = Files.list(parent)) {
            if (entries.findAny().isEmpty()) {
                Files.deleteIfExists(parent);
            }
        } catch (IOException ignored) {
            // Cleanup is best effort.
        }
    }

    public void delete(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        Path file = path(relativePath);
        try {
            Files.deleteIfExists(file);
            Path parent = file.getParent();
            if (parent != null && parent.startsWith(root) && !parent.equals(root)
                    && Files.isDirectory(parent)) {
                try (var entries = Files.list(parent)) {
                    if (entries.findAny().isEmpty()) {
                        Files.deleteIfExists(parent);
                    }
                }
            }
        } catch (IOException error) {
            // 清理失败不能阻断数据库删除，但必须留痕：否则用户以为文件已删，
            // 实际留下孤儿文件长期占空间。
            org.slf4j.LoggerFactory.getLogger(SourceFileStorage.class)
                    .warn("源文件删除失败 path={} error={}", relativePath, error.getMessage());
        }
    }

    public static String safeDirectoryName(String name) {
        String value = name == null ? "" : name.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|]", "_");
        value = value.replaceAll("\\s+", " ").trim();
        if (value.isBlank() || value.equals(".") || value.equals("..")) {
            value = "知识库";
        }
        return value.length() > 80 ? value.substring(0, 80).trim() : value;
    }

    private String safeFileName(String name) {
        String value = name == null ? "" : name.trim();
        int separator = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (separator >= 0) {
            value = value.substring(separator + 1);
        }
        value = value.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (value.isBlank()) {
            value = "file";
        }
        return value;
    }

    private String extensionOf(String name) {
        int index = name.lastIndexOf('.');
        return index > 0 ? name.substring(index).toLowerCase(Locale.ROOT) : "";
    }

    private String stripExtension(String name) {
        int index = name.lastIndexOf('.');
        return index > 0 ? name.substring(0, index) : name;
    }
}
