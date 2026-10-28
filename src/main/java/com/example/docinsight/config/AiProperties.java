package com.example.docinsight.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AiProperties(Ai ai, Embedding embedding, Rag rag) {

    public record Ai(String baseUrl, String apiKey, String model, int timeoutSeconds, int maxToolRounds) {
    }

    public record Embedding(String baseUrl, String apiKey, String model) {
        public boolean enabled() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Rag(int topK, int maxChunkSize, int chunkOverlap) {
    }

    public boolean llmEnabled() {
        return ai.apiKey() != null && !ai.apiKey().isBlank();
    }
}
