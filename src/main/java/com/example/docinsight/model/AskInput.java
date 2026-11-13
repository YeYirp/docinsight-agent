package com.example.docinsight.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AskInput(
        @NotBlank(message = "question 不能为空")
        @Size(max = 1000, message = "问题最多 1000 字")
        String question,
        String docId,
        String sessionId,
        @Size(max = 20, message = "一次最多选择 20 个知识库")
        List<String> knowledgeBaseIds
) {
    public AskInput(String question, String docId, String sessionId) {
        this(question, docId, sessionId, List.of());
    }
}
