package com.example.docinsight.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class AiConfig {

    /** 没有配置 key 时返回 null，AgentService 会走离线抽取式回答。 */
    @Bean
    public ChatModel chatModel(AiProperties props) {
        if (!props.llmEnabled()) {
            return null;
        }
        return OpenAiChatModel.builder()
                .baseUrl(props.ai().baseUrl())
                .apiKey(props.ai().apiKey())
                .modelName(props.ai().model())
                .timeout(Duration.ofSeconds(props.ai().timeoutSeconds()))
                .maxRetries(0)
                .build();
    }

    @Bean
    public StreamingChatModel streamingChatModel(AiProperties props) {
        if (!props.llmEnabled()) {
            return null;
        }
        return OpenAiStreamingChatModel.builder()
                .baseUrl(props.ai().baseUrl())
                .apiKey(props.ai().apiKey())
                .modelName(props.ai().model())
                .timeout(Duration.ofSeconds(props.ai().timeoutSeconds()))
                .build();
    }
}
