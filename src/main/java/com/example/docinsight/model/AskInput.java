package com.example.docinsight.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AskInput(
        @NotBlank(message = "question 不能为空")
        @Size(max = 1000, message = "问题最多 1000 字")
        String question,
        String docId,
        String sessionId
) {
}
