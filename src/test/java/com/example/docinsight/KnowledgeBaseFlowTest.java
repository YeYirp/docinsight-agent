package com.example.docinsight;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.ai.api-key=", "app.embedding.api-key="})
class KnowledgeBaseFlowTest {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createsKnowledgeBasesAndChatsAcrossSelectedScopes() throws Exception {
        JsonNode ops = createKnowledgeBase("运维知识库-" + System.nanoTime(), "备份与恢复");
        JsonNode account = createKnowledgeBase("账号知识库-" + System.nanoTime(), "账号安全");
        String opsId = ops.path("id").asText();
        String accountId = account.path("id").asText();

        assertThat(upload(opsId, "ops-manual.md", """
                # 运维手册
                ## 备份策略
                系统每天凌晨执行全量备份。
                """).statusCode()).isEqualTo(200);
        assertThat(upload(accountId, "account-manual.md", """
                # 账号手册
                ## 账号锁定
                连续五次密码错误会锁定账号三十分钟。
                """).statusCode()).isEqualTo(200);

        JsonNode opsDocuments = get("/api/knowledge-bases/" + opsId + "/documents");
        assertThat(opsDocuments.toString()).contains("ops-manual.md");
        assertThat(opsDocuments.toString()).doesNotContain("account-manual.md");

        String multiScopeAsk = ask("备份和账号锁定分别怎么处理？", opsId, accountId);
        assertThat(multiScopeAsk).contains("event:sources");
        assertThat(multiScopeAsk).contains("ops-manual.md");
        assertThat(multiScopeAsk).contains("account-manual.md");
        assertThat(multiScopeAsk).contains("event:done");

        String opsOnlyAsk = ask("账号锁定怎么处理？", opsId);
        assertThat(opsOnlyAsk).contains("event:sources");
        assertThat(opsOnlyAsk).doesNotContain("account-manual.md");
    }

    private JsonNode createKnowledgeBase(String name, String description) throws Exception {
        String body = mapper.writeValueAsString(new KnowledgeBaseBody(name, description));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/knowledge-bases"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(20))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(201);
        return mapper.readTree(response.body());
    }

    private HttpResponse<String> upload(String knowledgeBaseId, String filename, String content) throws Exception {
        String boundary = "----docinsight" + System.nanoTime();
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: text/markdown\r\n\r\n";
        byte[] body = concat(
                head.getBytes(StandardCharsets.UTF_8),
                content.getBytes(StandardCharsets.UTF_8),
                ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port
                                + "/api/knowledge-bases/" + knowledgeBaseId + "/documents"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(30))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .timeout(Duration.ofSeconds(20))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private String ask(String question, String... knowledgeBaseIds) throws Exception {
        String ids = String.join("\",\"", knowledgeBaseIds);
        String json = "{\"question\":\""
                + question + "\",\"knowledgeBaseIds\":[\"" + ids + "\"]}";
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/ask"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(60))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
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

    private record KnowledgeBaseBody(String name, String description) {
    }
}
