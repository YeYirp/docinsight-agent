package com.example.docinsight.service;

import com.example.docinsight.config.AiProperties;
import com.example.docinsight.model.AskInput;
import com.example.docinsight.model.Chunk;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 主流程：问题理解 → 检索 →（可选）工具调用 → 流式回答 + 引用。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private static final String SYSTEM_PROMPT = """
            你是企业文档问答助手。规则：
            1. 只依据给定的资料回答，资料中没有的信息明确说"文档中未提及"；
            2. 每一条结论后面用 [1][2] 标注引用的资料编号；
            3. 不要编造页码、条款号或数字；
            4. 回答控制在 300 字以内，先给结论再给要点。
            """;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectProvider<StreamingChatModel> streamingProvider;
    private final RetrievalService retrieval;
    private final DocumentStore store;
    private final Tools tools;
    private final MetricsService metrics;
    private final AiProperties props;
    private final ObjectMapper mapper;

    private final ExecutorService executor = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "agent-" + UUID.randomUUID().toString().substring(0, 4));
        t.setDaemon(true);
        return t;
    });

    public AgentService(ObjectProvider<ChatModel> chatModelProvider,
                        ObjectProvider<StreamingChatModel> streamingProvider,
                        RetrievalService retrieval,
                        DocumentStore store,
                        Tools tools,
                        MetricsService metrics,
                        AiProperties props,
                        ObjectMapper mapper) {
        this.chatModelProvider = chatModelProvider;
        this.streamingProvider = streamingProvider;
        this.retrieval = retrieval;
        this.store = store;
        this.tools = tools;
        this.metrics = metrics;
        this.props = props;
        this.mapper = mapper;
    }

    public SseEmitter ask(AskInput input) {
        SseEmitter emitter = new SseEmitter(180_000L);
        emitter.onTimeout(emitter::complete);
        executor.submit(() -> run(input, emitter));
        return emitter;
    }

    private void run(AskInput input, SseEmitter emitter) {
        long started = System.currentTimeMillis();
        metrics.request();
        try {
            if (store.totalChunks() == 0) {
                send(emitter, "error", Map.of("message", "还没有上传文档，请先上传 PDF / Word / Markdown"));
                emitter.complete();
                return;
            }

            stage(emitter, "理解问题", "提取检索关键词");
            Set<String> keywords = keywords(input.question());

            stage(emitter, "检索文档", "混合检索：向量 + 关键词");
            List<RetrievalService.Hit> hits = retrieval.search(
                    input.question(), keywords, input.docId(), props.rag().topK());

            List<Map<String, Object>> sources = new ArrayList<>();
            for (int i = 0; i < hits.size(); i++) {
                Chunk chunk = hits.get(i).chunk();
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("index", i + 1);
                source.put("docName", chunk.docName());
                source.put("heading", chunk.heading());
                source.put("location", chunk.location());
                source.put("snippet", snippet(chunk.text()));
                source.put("score", Math.round(hits.get(i).score() * 1000) / 1000.0);
                sources.add(source);
            }
            send(emitter, "sources", sources);

            stage(emitter, "生成回答", "依据资料组织答案");
            String answer = llmReady()
                    ? llmAnswer(input.question(), hits, emitter)
                    : extractiveAnswer(hits, emitter);

            metrics.latency(System.currentTimeMillis() - started);
            send(emitter, "done", Map.of(
                    "elapsedMs", System.currentTimeMillis() - started,
                    "citations", sources.size()));
            emitter.complete();
        } catch (Exception e) {
            metrics.error();
            log.warn("ask_failed: {}", e.getMessage(), e);
            send(emitter, "error", Map.of("message", "回答生成失败，请稍后重试"));
            emitter.completeWithError(e);
        }
    }

    private boolean llmReady() {
        return props.llmEnabled()
                && chatModelProvider.getIfAvailable() != null
                && streamingProvider.getIfAvailable() != null;
    }

    private String llmAnswer(String question, List<RetrievalService.Hit> hits, SseEmitter emitter)
            throws Exception {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        StreamingChatModel streaming = streamingProvider.getIfAvailable();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        messages.add(UserMessage.from(context(question, hits)));

        messages = toolLoop(chatModel, messages, emitter);

        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        StringBuilder answer = new StringBuilder();
        streaming.chat(ChatRequest.builder().messages(messages).build(),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partial) {
                        answer.append(partial);
                        send(emitter, "delta", Map.of("text", partial));
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        future.complete(response);
                    }

                    @Override
                    public void onError(Throwable error) {
                        future.completeExceptionally(error);
                    }
                });

        ChatResponse response = future.get(props.ai().timeoutSeconds(), TimeUnit.SECONDS);
        if (response.tokenUsage() != null) {
            metrics.tokens(response.tokenUsage().inputTokenCount(), response.tokenUsage().outputTokenCount());
        }
        return answer.toString();
    }

    /** 工具最多调 maxToolRounds 轮；到上限就禁用工具，强制基于已有资料总结。 */
    private List<ChatMessage> toolLoop(ChatModel chatModel, List<ChatMessage> messages, SseEmitter emitter) {
        List<ToolSpecification> specs = tools.specifications();
        int maxRounds = Math.max(1, props.ai().maxToolRounds());

        for (int round = 0; round < maxRounds; round++) {
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(specs)
                    .build());
            if (response.tokenUsage() != null) {
                metrics.tokens(response.tokenUsage().inputTokenCount(), response.tokenUsage().outputTokenCount());
            }
            AiMessage aiMessage = response.aiMessage();
            if (!aiMessage.hasToolExecutionRequests()) {
                return messages;
            }
            messages.add(aiMessage);
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                if (!Tools.SEARCH.equals(request.name())) {
                    throw new IllegalStateException("模型请求了未授权工具: " + request.name());
                }
                stage(emitter, "调用工具", request.name());
                metrics.toolCall();
                messages.add(ToolExecutionResultMessage.from(request,
                        tools.execute(request.name(), request.arguments())));
            }
        }
        messages.add(UserMessage.from("请直接依据上述资料回答，不要再调用工具。"));
        return messages;
    }

    /** 没有配置模型时，用检索结果拼一个抽取式回答，保证链路可演示。 */
    private String extractiveAnswer(List<RetrievalService.Hit> hits, SseEmitter emitter) throws InterruptedException {
        if (hits.isEmpty()) {
            String text = "没有检索到相关内容。请确认文档是否包含该主题，或换一种说法再问。";
            stream(emitter, text);
            return text;
        }
        StringBuilder sb = new StringBuilder("根据文档，可以这样理解：\n\n");
        int index = 1;
        for (RetrievalService.Hit hit : hits) {
            Chunk chunk = hit.chunk();
            String body = chunk.text().replace("\n", " ").trim();
            if (body.length() > 120) {
                body = body.substring(0, 120) + "…";
            }
            sb.append("[").append(index++).append("] ")
                    .append(chunk.heading().isBlank() ? "" : chunk.heading() + "：")
                    .append(body)
                    .append("（").append(chunk.location()).append("）\n");
        }
        sb.append("\n（当前未配置模型 key，以上为原文摘录。）");
        String text = sb.toString();
        stream(emitter, text);
        return text;
    }

    private void stream(SseEmitter emitter, String text) throws InterruptedException {
        for (int i = 0; i < text.length(); i += 12) {
            send(emitter, "delta", Map.of("text", text.substring(i, Math.min(text.length(), i + 12))));
            Thread.sleep(18);
        }
    }

    private String context(String question, List<RetrievalService.Hit> hits) {
        StringBuilder sb = new StringBuilder("资料：\n");
        for (int i = 0; i < hits.size(); i++) {
            Chunk chunk = hits.get(i).chunk();
            sb.append("[").append(i + 1).append("] 《").append(chunk.docName()).append("》")
                    .append(chunk.heading().isBlank() ? "" : " / " + chunk.heading())
                    .append("（").append(chunk.location()).append("）\n")
                    .append(chunk.text().replace("\n", " "))
                    .append("\n\n");
        }
        sb.append("问题：").append(question);
        return sb.toString();
    }

    /** 领域词 + 二字片段，够用且不引入分词依赖。 */
    private Set<String> keywords(String question) {
        Set<String> keywords = new LinkedHashSet<>();
        String[] domain = {"备份", "权限", "告警", "部署", "安装", "账号", "设备", "恢复",
                "日志", "接口", "配置", "升级", "故障", "排查"};
        for (String word : domain) {
            if (question.contains(word)) {
                keywords.add(word);
            }
        }
        // 再补问题里的二字片段，避免只靠领域词导致相近章节分不开
        if (keywords.size() < 6) {
            for (int i = 0; i + 2 <= question.length() && keywords.size() < 6; i++) {
                String gram = question.substring(i, i + 2);
                if (gram.matches("[\\u4e00-\\u9fa5]{2}")) {
                    keywords.add(gram);
                }
            }
        }
        return keywords;
    }

    private String snippet(String text) {
        String flat = text.replace("\n", " ").trim();
        return flat.length() > 80 ? flat.substring(0, 80) + "…" : flat;
    }

    private void stage(SseEmitter emitter, String name, String detail) {
        send(emitter, "stage", Map.of("name", name, "detail", detail));
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data(mapper.writeValueAsString(data), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("sse_send_failed event={}", event);
        }
    }
}
