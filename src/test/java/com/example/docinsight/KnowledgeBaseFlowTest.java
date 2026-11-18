package com.example.docinsight;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.ai.api-key=",
                "app.embedding.api-key=",
                "spring.datasource.url=jdbc:sqlite:target/test-data/knowledge-base-flow.db"
        })
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

        String updatedName = "运维资料库-" + System.nanoTime();
        JsonNode updated = updateKnowledgeBase(
                opsId, updatedName, "备份、恢复与账号操作", null);
        assertThat(updated.path("name").asText()).isEqualTo(updatedName);
        assertThat(updated.path("description").asText()).isEqualTo("备份、恢复与账号操作");
        JsonNode storage = get("/api/knowledge-bases/" + opsId + "/storage");
        assertThat(storage.path("path").asText()).contains(updatedName);

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

        Path customPath = Path.of("target", "test-data",
                "custom-kb-" + System.nanoTime()).toAbsolutePath();
        JsonNode customUpdated = updateKnowledgeBase(
                opsId, updatedName, "自定义原文件目录", customPath.toString());
        assertThat(customUpdated.path("name").asText()).isEqualTo(updatedName);
        JsonNode customStorage = get("/api/knowledge-bases/" + opsId + "/storage");
        assertThat(Path.of(customStorage.path("path").asText()).toAbsolutePath().normalize())
                .isEqualTo(customPath.normalize());
        assertThat(Files.exists(customPath.resolve("ops-manual.md"))).isTrue();

        JsonNode directories = get("/api/settings/directories?path="
                + URLEncoder.encode(customPath.toString(), StandardCharsets.UTF_8));
        assertThat(Path.of(directories.path("current").asText()).toAbsolutePath().normalize())
                .isEqualTo(customPath.normalize());
        assertThat(directories.path("directories").isArray()).isTrue();
        assertThat(directories.path("roots").isArray()).isTrue();
        assertThat(directories.path("roots").isEmpty()).isFalse();

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

        HttpResponse<String> home = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/"))
                .GET()
                .timeout(Duration.ofSeconds(20))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(home.statusCode()).isEqualTo(200);
        assertThat(home.headers().firstValue("Cache-Control").orElse(""))
                .contains("no-cache");
    }

    private JsonNode createKnowledgeBase(String name, String description) throws Exception {
        String body = mapper.writeValueAsString(
                new KnowledgeBaseBody(name, description, null));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/knowledge-bases"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(20))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(201);
        return mapper.readTree(response.body());
    }

    private JsonNode updateKnowledgeBase(String id, String name, String description,
                                         String sourcePath)
            throws Exception {
        String body = mapper.writeValueAsString(
                new KnowledgeBaseBody(name, description, sourcePath));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port
                                + "/api/knowledge-bases/" + id))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(20))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
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

    private record KnowledgeBaseBody(String name, String description, String sourcePath) {
    }
}
