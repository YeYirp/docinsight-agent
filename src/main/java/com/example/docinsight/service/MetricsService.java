package com.example.docinsight.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MetricsService {

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong toolCalls = new AtomicLong();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong totalLatency = new AtomicLong();

    public void request() {
        requests.incrementAndGet();
    }

    public void error() {
        errors.incrementAndGet();
    }

    public void toolCall() {
        toolCalls.incrementAndGet();
    }

    public void tokens(Integer in, Integer out) {
        if (in != null) inputTokens.addAndGet(in);
        if (out != null) outputTokens.addAndGet(out);
    }

    public void latency(long ms) {
        totalLatency.addAndGet(ms);
    }

    public Map<String, Object> snapshot() {
        long req = requests.get();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("requests", req);
        map.put("errors", errors.get());
        map.put("toolCalls", toolCalls.get());
        map.put("inputTokens", inputTokens.get());
        map.put("outputTokens", outputTokens.get());
        map.put("avgLatencyMs", req == 0 ? 0 : totalLatency.get() / req);
        return map;
    }
}
