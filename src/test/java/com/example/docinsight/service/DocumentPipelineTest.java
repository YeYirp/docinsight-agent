package com.example.docinsight.service;

import com.example.docinsight.config.AiProperties;
import com.example.docinsight.model.Chunk;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentPipelineTest {

    private final AiProperties props = new AiProperties(
            new AiProperties.Ai("http://localhost", "", "test", 30, 3),
            new AiProperties.Embedding("http://localhost", "", "bge"),
            new AiProperties.Rag(4, 200, 40));

    private static final String SAMPLE = """
            # 产品手册

            这是开头的说明文字。

            ## 1. 备份策略

            系统每日凌晨执行全量备份。
            每小时执行增量备份。

            ## 2. 账号锁定

            连续 5 次密码错误会锁定账号 30 分钟。
            """;

    @Test
    void parsesHeadingsAndKeepsLocation() throws Exception {
        DocumentParser parser = new DocumentParser();
        DocumentParser.Parsed parsed = parser.parse("manual.md", SAMPLE.getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.blocks()).isNotEmpty();
        assertThat(parsed.blocks()).anyMatch(block -> block.heading() && block.text().contains("备份策略"));
        assertThat(parsed.blocks()).allMatch(block -> !block.location().isBlank());
    }

    @Test
    void chunkKeepsHeadingPath() throws Exception {
        DocumentParser parser = new DocumentParser();
        DocumentParser.Parsed parsed = parser.parse("manual.md", SAMPLE.getBytes(StandardCharsets.UTF_8));
        List<Chunk> chunks = new Chunker(props).chunk("doc1", "manual.md", parsed.blocks());

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).anyMatch(chunk -> chunk.heading().contains("备份策略"));
        assertThat(chunks).allMatch(chunk -> chunk.text().length() <= 240);
        assertThat(chunks.get(0).docId()).isEqualTo("doc1");
    }
}
