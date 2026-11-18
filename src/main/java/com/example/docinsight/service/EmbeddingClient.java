package com.example.docinsight.service;

import com.example.docinsight.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的 /embeddings 客户端。没配置 key 或调用失败都返回 null，
 * 由检索层降级为关键词匹配。
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final AiProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public EmbeddingClient(AiProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public boolean enabled() {
        return props.embedding().enabled();
    }

    public List<double[]> embed(List<String> inputs) {
        if (!enabled() || inputs.isEmpty()) {
            return null;
        }
        int batchSize = Math.max(1, props.embedding().batchSize());
        List<double[]> vectors = new ArrayList<>(inputs.size());
        for (int offset = 0; offset < inputs.size(); offset += batchSize) {
            List<String> batch = inputs.subList(offset,
                    Math.min(inputs.size(), offset + batchSize));
            List<double[]> embedded = embedBatch(batch);
            if (embedded == null || embedded.size() != batch.size()) {
                return null;
            }
            vectors.addAll(embedded);
        }
        return vectors;
    }

    private List<double[]> embedBatch(List<String> inputs) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", props.embedding().model(),
                    "input", inputs
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(strip(props.embedding().baseUrl()) + "/embeddings"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + props.embedding().apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("embedding_http_error status={}", response.statusCode());
                return null;
            }
            JsonNode data = mapper.readTree(response.body()).get("data");
            if (data == null || !data.isArray()) {
                return null;
            }
            List<double[]> vectors = new ArrayList<>(data.size());
            for (JsonNode item : data) {
                JsonNode embedding = item.get("embedding");
                if (embedding == null || !embedding.isArray()) {
                    log.warn("embedding_response_missing_vector");
                    return null;
                }
                double[] vector = new double[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = embedding.get(i).asDouble();
                }
                if (!validDimension(vector)) {
                    log.warn("embedding_dimension_mismatch expected={} actual={}",
                            props.embedding().dimension(), vector.length);
                    return null;
                }
                vectors.add(vector);
            }
            return vectors.size() == inputs.size() ? vectors : null;
        } catch (Exception e) {
            log.warn("embedding_failed: {}", e.getMessage());
            return null;
        }
    }

    private String strip(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private boolean validDimension(double[] vector) {
        if (vector.length != props.embedding().dimension()) {
            return false;
        }
        for (double value : vector) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }
}
