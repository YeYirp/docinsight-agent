package com.example.docinsight.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateKnowledgeBaseInput(
        @NotBlank(message = "知识库名称不能为空")
        @Size(max = 60, message = "知识库名称最多 60 字")
        String name,
        @Size(max = 240, message = "知识库描述最多 240 字")
        String description
) {
}
