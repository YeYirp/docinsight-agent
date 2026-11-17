package com.example.docinsight.service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/** 文档格式与扩展名的唯一来源，上传校验和解析器都从这里读取。 */
public enum DocumentFormat {

    PDF(".pdf", "PDF"),
    DOCX(".docx", "DOCX"),
    MARKDOWN(".md", "MD"),
    TEXT(".txt", "TXT");

    private final String extension;
    private final String label;

    DocumentFormat(String extension, String label) {
        this.extension = extension;
        this.label = label;
    }

    public String extension() {
        return extension;
    }

    public String label() {
        return label;
    }

    public static Optional<DocumentFormat> fromFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return Optional.empty();
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(format -> lower.endsWith(format.extension))
                .findFirst();
    }

    public static boolean supports(String filename) {
        return fromFilename(filename).isPresent();
    }

    public static String supportedLabels() {
        return Arrays.stream(values())
                .map(DocumentFormat::label)
                .collect(Collectors.joining(" / "));
    }
}
