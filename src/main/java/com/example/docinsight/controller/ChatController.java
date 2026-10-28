package com.example.docinsight.controller;

import com.example.docinsight.model.AskInput;
import com.example.docinsight.service.AgentService;
import com.example.docinsight.service.DocumentStore;
import com.example.docinsight.service.EvalService;
import com.example.docinsight.service.MetricsService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final AgentService agentService;
    private final MetricsService metrics;
    private final DocumentStore store;
    private final EvalService evalService;

    public ChatController(AgentService agentService, MetricsService metrics,
                          DocumentStore store, EvalService evalService) {
        this.agentService = agentService;
        this.metrics = metrics;
        this.store = store;
        this.evalService = evalService;
    }

    /** SSE：stage / sources / delta / done / error */
    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@Valid @RequestBody AskInput input) {
        return agentService.ask(input);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("documents", store.list().size());
        body.put("chunks", store.totalChunks());
        body.put("evalCases", evalService.caseCount());
        return body;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return metrics.snapshot();
    }

    /** 检索层评测：返回 Hit Rate@K 与逐题明细。 */
    @GetMapping("/eval")
    public Map<String, Object> eval(@RequestParam(defaultValue = "3") int topK) {
        return evalService.run(topK);
    }
}
