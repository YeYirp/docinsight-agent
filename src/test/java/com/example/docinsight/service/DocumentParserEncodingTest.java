package com.example.docinsight.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentParserEncodingTest {

    @Test
    void detectsUtf8WithBom() {
        byte[] bytes = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a'};
        assertThat(DocumentParser.detectCharset(bytes)).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void detectsGb18030ForChineseWindowsText() throws Exception {
        byte[] bytes = "备份策略每日执行".getBytes(Charset.forName("GB18030"));
        assertThat(DocumentParser.detectCharset(bytes).name()).isEqualTo("GB18030");
        assertThat(new String(bytes, DocumentParser.detectCharset(bytes))).isEqualTo("备份策略每日执行");
    }

    @Test
    void usesUtf8ForPlainAscii() {
        assertThat(DocumentParser.detectCharset("hello".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void filtersRepeatedHeaderAndFooterLines() {
        List<List<String>> pages = List.of(
                List.of("公司机密", "第一节", "正文一", "第 1 页"),
                List.of("公司机密", "第二节", "正文二", "第 2 页"),
                List.of("公司机密", "第三节", "正文三", "第 3 页"));
        assertThat(DocumentParser.findRepeatedEdges(pages))
                .contains("公司机密")
                .doesNotContain("正文一", "第一节");
    }
}
