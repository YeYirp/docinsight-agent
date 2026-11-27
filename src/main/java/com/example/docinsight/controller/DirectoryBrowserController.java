package com.example.docinsight.controller;

import com.example.docinsight.service.SourceFileStorage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class DirectoryBrowserController {

    private final SourceFileStorage sourceFiles;

    public DirectoryBrowserController(SourceFileStorage sourceFiles) {
        this.sourceFiles = sourceFiles;
    }

    /**
     * 目录浏览。默认只允许浏览原文件根目录及其子目录（防止无鉴权时全盘枚举）；
     * 需要选择根外目录时由 app.storage.allow-outside-root=true 显式放开。
     */
    @GetMapping("/directories")
    public Map<String, Object> directories(
            @RequestParam(required = false) String path) throws IOException {
        Path fallback = Path.of(sourceFiles.rootPath()).toAbsolutePath().normalize();
        Files.createDirectories(fallback);
        Path current = path == null || path.isBlank()
                ? fallback : Path.of(path).toAbsolutePath().normalize();
        if (!sourceFiles.allowsOutsideRoot() && !current.startsWith(fallback)) {
            current = fallback;
        }
        if (!Files.isDirectory(current)) {
            Path parent = current.getParent();
            current = parent != null && Files.isDirectory(parent) ? parent : fallback;
        }
        if (!Files.isDirectory(current)) {
            current = fallback;
        }

        List<Map<String, String>> children = new ArrayList<>();
        try (var entries = Files.list(current)) {
            entries.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(pathValue ->
                            pathValue.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(pathValue -> {
                        Map<String, String> item = new LinkedHashMap<>();
                        item.put("name", pathValue.getFileName().toString());
                        item.put("path", pathValue.toAbsolutePath().normalize().toString());
                        children.add(item);
                    });
        }

        List<String> roots = new ArrayList<>();
        if (sourceFiles.allowsOutsideRoot()) {
            for (File root : File.listRoots()) {
                roots.add(root.toPath().toAbsolutePath().normalize().toString());
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("current", current.toString());
        body.put("parent", current.getParent() == null
                ? null : current.getParent().toAbsolutePath().normalize().toString());
        body.put("directories", children);
        body.put("roots", roots);
        return body;
    }
}
