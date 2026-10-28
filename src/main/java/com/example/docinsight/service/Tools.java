package com.example.docinsight.service;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** 只注册一个联网搜索工具，演示 Function Calling 的白名单与轮次控制。 */
@Component
public class Tools {

    public static final String SEARCH = "webSearch";

    private final ObjectMapper mapper;

    public Tools(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<ToolSpecification> specifications() {
        return List.of(ToolSpecification.builder()
                .name(SEARCH)
                .description("当文档中没有答案时，联网搜索公开资料补充背景信息")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("query", "搜索关键词")
                        .required("query")
                        .build())
                .build());
    }

    public String execute(String name, String arguments) {
        if (!SEARCH.equals(name)) {
            return "未知工具：" + name;
        }
        JsonNode args;
        try {
            args = mapper.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
        } catch (Exception e) {
            args = mapper.createObjectNode();
        }
        String query = args.path("query").asText("");
        return mockSearch(query);
    }

    /** 离线示例数据；接真实搜索时只替换这个方法。 */
    private String mockSearch(String query) {
        Map<String, String> samples = Map.of(
                "备份", "公开资料：备份策略通常采用全量 + 增量，并定期做恢复演练。",
                "权限", "公开资料：RBAC 中角色与权限是多对多，数据权限通常按组织或部门隔离。",
                "告警", "公开资料：告警应设置分级、抑制与静默，避免告警风暴。"
        );
        return samples.entrySet().stream()
                .filter(e -> query.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("未检索到与「" + query + "」相关的公开资料。");
    }
}
