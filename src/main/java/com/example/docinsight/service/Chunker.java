package com.example.docinsight.service;

import com.example.docinsight.config.AiProperties;
import com.example.docinsight.model.Chunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 按标题层级切分：同一标题下的段落合成一块；过长再按长度切，并保留 overlap。
 * 每块都带 heading 与 location，前端可以直接跳转到原文位置。
 */
@Component
public class Chunker {

    private final AiProperties props;

    public Chunker(AiProperties props) {
        this.props = props;
    }

    public List<Chunk> chunk(String docId, String docName, List<DocumentParser.Block> blocks) {
        int maxSize = Math.max(200, props.rag().maxChunkSize());
        int overlap = Math.max(0, props.rag().chunkOverlap());

        List<Chunk> chunks = new ArrayList<>();
        List<String> headingPath = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String startLocation = null;
        String endLocation = null;

        for (DocumentParser.Block block : blocks) {
            if (block.heading()) {
                flush(chunks, docId, docName, headingPath, body, startLocation, endLocation, maxSize, overlap);
                int level = Math.max(1, block.level());
                while (headingPath.size() >= level) {
                    headingPath.remove(headingPath.size() - 1);
                }
                headingPath.add(block.text());
                body = new StringBuilder();
                startLocation = block.location();
                endLocation = block.location();
                continue;
            }
            if (body.length() + block.text().length() > maxSize && body.length() > 0) {
                flush(chunks, docId, docName, headingPath, body, startLocation, endLocation, maxSize, overlap);
                body = new StringBuilder();
                startLocation = block.location();
            }
            if (body.length() == 0) {
                startLocation = block.location();
            }
            body.append(block.text()).append('\n');
            endLocation = block.location();
        }
        flush(chunks, docId, docName, headingPath, body, startLocation, endLocation, maxSize, overlap);
        return chunks;
    }

    private void flush(List<Chunk> out, String docId, String docName, List<String> headingPath,
                       StringBuilder body, String start, String end, int maxSize, int overlap) {
        String text = body.toString().trim();
        if (text.isEmpty()) {
            return;
        }
        String heading = String.join(" > ", headingPath);
        String location = formatLocation(start, end);

        int index = 0;
        int offset = 0;
        while (offset < text.length()) {
            int to = Math.min(text.length(), offset + maxSize);
            String slice = text.substring(offset, to).trim();
            if (!slice.isEmpty()) {
                out.add(new Chunk(docId + "#" + out.size(), docId, docName, heading, slice, location, null));
            }
            index++;
            if (to >= text.length()) {
                break;
            }
            offset = Math.max(offset + 1, to - overlap);
        }
    }

    private String formatLocation(String start, String end) {
        if (start == null) {
            return "";
        }
        if (end == null || start.equals(end)) {
            return start;
        }
        return start + " - " + end;
    }
}
