package com.example.docinsight.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "app")
public record AiProperties(Ai ai, Embedding embedding, Rag rag, Security security) {

    @ConstructorBinding
    public AiProperties {
    }

    public record Security(String apiToken) {
        public boolean enabled() {
            return apiToken != null && !apiToken.isBlank();
        }
    }

    /** 兼容只传 ai/embedding/rag 的既有调用方：默认不启用鉴权。 */
    public AiProperties(Ai ai, Embedding embedding, Rag rag) {
        this(ai, embedding, rag, new Security(null));
    }

    public record Ai(String baseUrl, String apiKey, String model, int timeoutSeconds, int maxToolRounds) {
    }

    public record Embedding(String baseUrl, String apiKey, String model,
                            int dimension, int batchSize) {
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
