package com.repopilot.core.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 表示模型本轮想调用工具，而不是直接结束回合。
 */
public record ToolCallModelResponse(
        List<ToolCall> toolCalls,
        Optional<TokenUsage> tokenUsage
) implements ModelResponse {

    public ToolCallModelResponse(List<ToolCall> toolCalls) {
        this(toolCalls, Optional.empty());
    }

    public ToolCallModelResponse(List<ToolCall> toolCalls, TokenUsage tokenUsage) {
        this(toolCalls, Optional.of(Objects.requireNonNull(tokenUsage, "tokenUsage must not be null.")));
    }

    public ToolCallModelResponse {
        if (toolCalls == null || toolCalls.isEmpty()) {
            throw new IllegalArgumentException("toolCalls must not be empty.");
        }
        toolCalls = List.copyOf(toolCalls);
        tokenUsage = tokenUsage == null ? Optional.empty() : tokenUsage;
    }
}
