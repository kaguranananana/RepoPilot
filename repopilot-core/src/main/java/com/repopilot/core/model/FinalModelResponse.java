package com.repopilot.core.model;

import java.util.Objects;
import java.util.Optional;

/**
 * 表示模型已经准备给出最终回答，不再请求工具。
 */
public record FinalModelResponse(
        String message,
        Optional<TokenUsage> tokenUsage
) implements ModelResponse {

    public FinalModelResponse(String message) {
        this(message, Optional.empty());
    }

    public FinalModelResponse(String message, TokenUsage tokenUsage) {
        this(message, Optional.of(Objects.requireNonNull(tokenUsage, "tokenUsage must not be null.")));
    }

    public FinalModelResponse {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank.");
        }
        tokenUsage = tokenUsage == null ? Optional.empty() : tokenUsage;
    }
}
