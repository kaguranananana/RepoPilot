package com.repopilot.core.model;

import java.util.List;

/**
 * 表示模型本轮想调用工具，而不是直接结束回合。
 */
public record ToolCallModelResponse(List<ToolCall> toolCalls) implements ModelResponse {

    public ToolCallModelResponse {
        if (toolCalls == null || toolCalls.isEmpty()) {
            throw new IllegalArgumentException("toolCalls must not be empty.");
        }
        toolCalls = List.copyOf(toolCalls);
    }
}

