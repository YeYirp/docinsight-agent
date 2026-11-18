package com.example.docinsight.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateKnowledgeBaseInput(
        @NotBlank(message = "知识库名称不能为空")
        @Size(max = 60, message = "知识库名称最多 60 字")
        String name,
        @Size(max = 240, message = "知识库描述最多 240 字")
        String description,
        @Size(max = 500, message = "原文件路径最多 500 字")
        String sourcePath
) {
}
