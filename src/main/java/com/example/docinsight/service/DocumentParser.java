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

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return parsePdf(filename, bytes);
        }
        if (lower.endsWith(".docx")) {
            return parseDocx(filename, bytes);
        }
        return parseText(filename, bytes);
    }

    private Parsed parsePdf(String filename, byte[] bytes) throws IOException {
        List<Block> blocks = new ArrayList<>();
        int pages;
        try (PDDocument document = PDDocument.load(bytes)) {
            pages = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                String location = "第 " + page + " 页";
                for (String line : text.split("\\r?\\n")) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    boolean heading = isHeading(trimmed);
                    blocks.add(new Block(trimmed, heading, heading ? guessLevel(trimmed) : 0, location));
                }
            }
        }
        return new Parsed(filename, pages, blocks);
    }

    private Parsed parseDocx(String filename, byte[] bytes) throws IOException {
        List<Block> blocks = new ArrayList<>();
        int paragraphs = 0;
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
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
        return new Parsed(filename, Math.max(1, paragraphs / 40 + 1), blocks);
    }

    private Parsed parseText(String filename, byte[] bytes) {
        String content = new String(bytes, StandardCharsets.UTF_8);
        List<Block> blocks = new ArrayList<>();
        String[] lines = content.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String text = lines[i].trim();
            if (text.isEmpty()) {
                continue;
            }
            boolean heading = isHeading(text);
            blocks.add(new Block(text, heading, heading ? guessLevel(text) : 0, "第 " + (i + 1) + " 行"));
        }
        return new Parsed(filename, 1, blocks);
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
