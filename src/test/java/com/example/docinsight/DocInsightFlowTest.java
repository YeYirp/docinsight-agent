package com.example.docinsight;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端：上传示例文档 → 提问 → 校验 SSE 事件与引用。
 * 不需要任何外部服务（没有 key 时走抽取式回答）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.ai.api-key=", "app.embedding.api-key="})
class DocInsightFlowTest {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void uploadThenAskReturnsCitations() throws Exception {
        Path sample = Path.of("sample/示例产品手册.md");
        assertThat(Files.exists(sample)).isTrue();

        String boundary = "----test" + System.nanoTime();
        byte[] fileBytes = Files.readAllBytes(sample);
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"manual.md\"\r\n"
                + "Content-Type: text/markdown\r\n\r\n";
        byte[] body = concat(head.getBytes(StandardCharsets.UTF_8), fileBytes,
                ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> upload = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/docs/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(30))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(upload.statusCode()).isEqualTo(200);
        assertThat(upload.body()).contains("chunks");

        HttpResponse<String> ask = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/ask"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"question\":\"备份多久做一次？\"}", StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(60))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(ask.statusCode()).isEqualTo(200);
        assertThat(ask.body()).contains("event:stage");
        assertThat(ask.body()).contains("event:sources");
        assertThat(ask.body()).contains("event:delta");
        assertThat(ask.body()).contains("event:done");
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
}
