package com.example.docinsight.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 把 PDF / Word / 文本拆成「块」，并尽量识别标题层级。
 *
 * <p>PDF 逐页提取，保留页码；Word 按段落样式判断标题；
 * 标题识别不出来的文档也不会失败，会退化成纯段落切分。
 */
@Component
public class DocumentParser {

    public record Block(String text, boolean heading, int level, String location) {
    }

    public record Parsed(String name, int pages, List<Block> blocks) {
    }

    private static final Pattern[] HEADING_PATTERNS = {
            Pattern.compile("^第[一二三四五六七八九十百零\\d]+[章节篇部分]"),
            Pattern.compile("^\\d+(\\.\\d+){0,3}[\\s、.．]\\s*\\S{1,40}$"),
            Pattern.compile("^[一二三四五六七八九十]+[、.]\\s*\\S{1,40}$"),
            Pattern.compile("^#{1,6}\\s+\\S+")
    };

    public Parsed parse(String filename, byte[] bytes) throws IOException {
        return parse(filename, new ByteArrayInputStream(bytes));
    }

    public Parsed parse(String filename, InputStream input) throws IOException {
        DocumentFormat format = DocumentFormat.fromFilename(filename).orElse(DocumentFormat.TEXT);
        return switch (format) {
            case PDF -> parsePdf(filename, input);
            case DOCX -> parseDocx(filename, input);
            case MARKDOWN, TEXT -> parseText(filename, input);
            case MP4, MOV, MKV, WEBM, AVI, M4V ->
                    throw new IllegalArgumentException("视频文件需要走语音转写流程");
        };
    }

    private Parsed parsePdf(String filename, InputStream input) throws IOException {
        List<Block> blocks = new ArrayList<>();
        int pages;
        try (PDDocument document = PDDocument.load(input)) {
            pages = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            // 先收集每页的行，找出反复出现的首尾行（页眉/页脚/页码）再统一过滤。
            List<List<String>> pageLines = new ArrayList<>();
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                List<String> lines = new ArrayList<>();
                for (String line : stripper.getText(document).split("\\r?\\n")) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        lines.add(trimmed);
                    }
                }
                pageLines.add(lines);
            }
            java.util.Set<String> repeated = findRepeatedEdges(pageLines);
            for (int page = 1; page <= pages; page++) {
                String location = "第 " + page + " 页";
                for (String line : pageLines.get(page - 1)) {
                    if (repeated.contains(line)) {
                        continue;
                    }
                    boolean heading = isHeading(line);
                    blocks.add(new Block(line, heading, heading ? guessLevel(line) : 0, location));
                }
            }
        }
        return new Parsed(filename, pages, blocks);
    }

    /** 在超过一半页面重复出现的首尾行视为页眉/页脚，避免污染检索词与向量。 */
    static java.util.Set<String> findRepeatedEdges(List<List<String>> pageLines) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        int threshold = Math.max(2, (pageLines.size() + 1) / 2);
        for (List<String> lines : pageLines) {
            if (lines.isEmpty()) {
                continue;
            }
            java.util.Set<String> seen = new java.util.HashSet<>();
            int edgeCount = Math.min(3, lines.size());
            for (int i = 0; i < edgeCount; i++) {
                seen.add(lines.get(i));
            }
            for (int i = Math.max(0, lines.size() - edgeCount); i < lines.size(); i++) {
                seen.add(lines.get(i));
            }
            for (String line : seen) {
                counts.merge(line, 1, Integer::sum);
            }
        }
        java.util.Set<String> repeated = new java.util.HashSet<>();
        counts.forEach((line, count) -> {
            if (count >= threshold) {
                repeated.add(line);
            }
        });
        return repeated;
    }

    private Parsed parseDocx(String filename, InputStream input) throws IOException {
        List<Block> blocks = new ArrayList<>();
        int paragraphs = 0;
        try (XWPFDocument document = new XWPFDocument(input)) {
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    paragraphs++;
                    String text = paragraph.getText().trim();
                    if (text.isEmpty()) {
                        continue;
                    }
                    String style = paragraph.getStyle() == null ? "" : paragraph.getStyle().toLowerCase();
                    boolean heading = style.contains("heading") || style.contains("标题") || isHeading(text);
                    blocks.add(new Block(text, heading, heading ? styleLevel(style, text) : 0,
                            "第 " + paragraphs + " 段"));
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        StringBuilder line = new StringBuilder();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            if (!line.isEmpty()) {
                                line.append(" | ");
                            }
                            line.append(cell.getText().trim());
                        }
                        if (!line.isEmpty()) {
                            blocks.add(new Block(line.toString(), false, 0, "表格"));
                        }
                    }
                }
            }
        }
        // DOCX 没有可靠页码：location 用"第 N 段"，pages 只作文档规模估计，
        // 不冒充真实页数（回答提示词要求不编造页码）。
        return new Parsed(filename, Math.max(1, paragraphs / 40 + 1), blocks);
    }

    private Parsed parseText(String filename, InputStream input) throws IOException {
        List<Block> blocks = new ArrayList<>();
        // 中文 Windows 的 txt 常是 GBK；强制 UTF-8 会静默解析成乱码且不报错。
        // 先按 BOM 判断，否则用严格 UTF-8 试读，失败就回退 GB18030（GBK 超集）。
        byte[] bytes = input.readAllBytes();
        java.nio.charset.Charset charset = detectCharset(bytes);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(bytes), charset))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String text = line.trim();
                if (text.isEmpty()) {
                    continue;
                }
                boolean heading = isHeading(text);
                blocks.add(new Block(text, heading, heading ? guessLevel(text) : 0,
                        "第 " + lineNumber + " 行"));
            }
        }
        return new Parsed(filename, 1, blocks);
    }

    static java.nio.charset.Charset detectCharset(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return java.nio.charset.StandardCharsets.UTF_16LE;
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return java.nio.charset.StandardCharsets.UTF_16BE;
        }
        java.nio.charset.CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        try {
            strict.decode(java.nio.ByteBuffer.wrap(bytes));
            return StandardCharsets.UTF_8;
        } catch (java.nio.charset.CharacterCodingException e) {
            return java.nio.charset.Charset.forName("GB18030");
        }
    }

    static boolean isHeading(String text) {
        if (text.length() > 60) {
            return false;
        }
        for (Pattern pattern : HEADING_PATTERNS) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static int guessLevel(String text) {
        if (text.startsWith("#")) {
            int level = 0;
            while (level < text.length() && text.charAt(level) == '#') {
                level++;
            }
            return Math.min(6, level);
        }
        int dots = (int) text.chars().filter(c -> c == '.').count();
        return Math.min(4, dots + 1);
    }

    private static int styleLevel(String style, String text) {
        int level = 0;
        StringBuilder digits = new StringBuilder();
        for (char c : style.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (digits.length() > 0) {
                break;
            }
        }
        if (digits.length() > 0) {
            level = Integer.parseInt(digits.toString());
        }
        return level > 0 ? Math.min(6, level) : guessLevel(text);
    }
}
