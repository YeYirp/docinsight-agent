package com.example.docinsight.model;

import jakarta.validation.constraints.NotBlank;

public record UpdateDocumentContentInput(
        @NotBlank(message = "文件内容不能为空")
        String text
) {
}
