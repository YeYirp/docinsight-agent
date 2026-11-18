package com.example.docinsight.controller;

import com.example.docinsight.model.AskInput;
import com.example.docinsight.service.AgentService;
import com.example.docinsight.service.DocumentStore;
import com.example.docinsight.service.MetricsService;
import jakarta.validation.Valid;
import javax.sql.DataSource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final AgentService agentService;
    private final MetricsService metrics;
    private final DocumentStore store;
    private final DataSource dataSource;

    public ChatController(AgentService agentService, MetricsService metrics,
                          DocumentStore store, DataSource dataSource) {
        this.agentService = agentService;
        this.metrics = metrics;
        this.store = store;
        this.dataSource = dataSource;
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
        body.put("database", databaseStatus());
        body.put("documents", store.list().size());
        body.put("chunks", store.totalChunks());
        return body;
    }

    private Map<String, Object> databaseStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            status.put("status", "UP");
            status.put("product", "SQLite");
            status.put("sqliteVersion", scalar(connection, "SELECT sqlite_version()"));
            status.put("vecVersion", scalar(connection, "SELECT vec_version()"));
            status.put("fts5", tableExists(connection, "chunk_fts"));
            status.put("vectorTable", tableExists(connection, "chunk_vector"));
            String url = connection.getMetaData().getURL();
            status.put("path", url == null ? "" : url.replace("jdbc:sqlite:", ""));
            status.put("persistent", true);
        } catch (Exception error) {
            status.put("status", "DOWN");
            status.put("message", error.getMessage());
        }
        return status;
    }

    private String scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private boolean tableExists(Connection connection, String name) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM sqlite_master WHERE type IN ('table', 'view') AND name = ?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) > 0;
            }
        }
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return metrics.snapshot();
    }
}
