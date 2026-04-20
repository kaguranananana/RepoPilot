package com.repopilot.core.agent.loop;

import java.util.Objects;

/**
 * 一次工具调用循环检测的确定性结果。
 * 它只描述“当前工具调用 key 的连续重复状态”，不做语义判断。
 */
public record ToolCallLoopDetectionResult(
        boolean loopDetected,
        String toolName,
        String toolCallKey,
        int repeatCount,
        String argumentsSummary
) {

    public ToolCallLoopDetectionResult {
        toolName = requireNonBlank(toolName, "toolName must not be blank.");
        toolCallKey = requireNonBlank(toolCallKey, "toolCallKey must not be blank.");
        if (repeatCount <= 0) {
            throw new IllegalArgumentException("repeatCount must be greater than zero.");
        }
        argumentsSummary = Objects.requireNonNull(argumentsSummary, "argumentsSummary must not be null.");
    }

    private static String requireNonBlank(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
