package com.example.docinsight;

import com.example.docinsight.service.VideoTranscriber;
import com.example.docinsight.service.VideoTranscript;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.ai.api-key=",
                "app.embedding.api-key=",
                "spring.datasource.url=jdbc:sqlite:target/test-data/video-flow.db",
                "app.video.storage-dir=target/test-data/video-flow-media",
                "app.video.max-file-size-mb=10",
                "app.video.max-duration-minutes=120"
        })
@Import(VideoIngestionFlowTest.FakeVideoConfig.class)
class VideoIngestionFlowTest {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void uploadsVideoAsynchronouslyAndServesRangePlayback() throws Exception {
        String name = "视频知识库-" + System.nanoTime();
        JsonNode kb = createKnowledgeBase(name);
        String kbId = kb.path("id").asText();

        HttpResponse<String> upload = uploadVideo(kbId, "演示视频.mp4",
                new byte[]{0, 1, 2, 3, 4, 5, 6, 7});
        assertThat(upload.statusCode()).isEqualTo(202);
        JsonNode accepted = mapper.readTree(upload.body());
        String jobId = accepted.path("jobId").asText();
        String docId = accepted.path("docId").asText();
        assertThat(jobId).isNotBlank();

        JsonNode job = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            JsonNode current = get("/api/ingestion-jobs/" + jobId);
            if ("DONE".equals(current.path("status").asText())) {
                job = current;
                break;
            }
            if ("FAILED".equals(current.path("status").asText())) {
                throw new AssertionError("视频任务失败: " + current.path("error").asText());
            }
            Thread.sleep(200);
        }
        assertThat(job).isNotNull();
        assertThat(job.path("document").path("media").asBoolean()).isTrue();
        assertThat(job.path("document").path("durationMs").asLong()).isEqualTo(65_000);

        JsonNode documents = get("/api/knowledge-bases/" + kbId + "/documents");
        assertThat(documents.toString()).contains("演示视频.mp4").contains("\"media\":true");

        HttpResponse<byte[]> playback = getMedia(docId, null);
        assertThat(playback.statusCode()).isEqualTo(200);
        assertThat(playback.body()).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);

        HttpResponse<byte[]> range = getMedia(docId, "bytes=0-3");
        assertThat(range.statusCode()).isEqualTo(206);
        assertThat(range.body()).containsExactly(0, 1, 2, 3);

        assertThat(delete("/api/docs/" + docId).statusCode()).isEqualTo(200);
        assertThat(getMedia(docId, null).statusCode()).isEqualTo(404);
    }

    private JsonNode createKnowledgeBase(String name) throws Exception {
        String json = mapper.writeValueAsString(new KnowledgeBaseBody(name, "视频测试"));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/knowledge-bases"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(20))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(201);
        return mapper.readTree(response.body());
    }

    private HttpResponse<String> uploadVideo(String kbId, String filename, byte[] bytes)
            throws Exception {
        String boundary = "----docinsight" + System.nanoTime();
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: video/mp4\r\n\r\n";
        byte[] body = concat(
                head.getBytes(StandardCharsets.UTF_8),
                bytes,
                ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port
                                + "/api/knowledge-bases/" + kbId + "/documents"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(30))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<byte[]> getMedia(String docId, String range) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/docs/" + docId + "/media"))
                .GET()
                .timeout(Duration.ofSeconds(20));
        if (range != null) {
            builder.header("Range", range);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
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

    private HttpResponse<String> delete(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .DELETE()
                .timeout(Duration.ofSeconds(20))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

    @TestConfiguration
    static class FakeVideoConfig {

        @Bean
        @Primary
        VideoTranscriber fakeVideoTranscriber() {
            return path -> new VideoTranscript(65, "zh", List.of(
                    new VideoTranscript.Segment(0, 3, "视频开头介绍产品背景。"),
                    new VideoTranscript.Segment(61, 64, "一分钟时演示操作步骤。")));
        }
    }
}
