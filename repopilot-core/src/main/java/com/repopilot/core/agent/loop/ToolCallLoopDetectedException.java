package com.repopilot.core.agent.loop;

import java.util.Objects;

/**
 * 连续重复工具调用命中阈值时抛出的明确终止异常。
 * 这里不继承工具执行错误语义，避免把循环误报成普通工具失败。
 */
public final class ToolCallLoopDetectedException extends RuntimeException {

    private final ToolCallLoopDetectionResult result;

    public ToolCallLoopDetectedException(ToolCallLoopDetectionResult result) {
        super(formatMessage(result));
        this.result = Objects.requireNonNull(result, "result must not be null.");
    }

    public ToolCallLoopDetectionResult result() {
        return result;
    }

    private static String formatMessage(ToolCallLoopDetectionResult result) {
        Objects.requireNonNull(result, "result must not be null.");
        return "连续重复工具调用导致终止: toolName=%s, repeatCount=%d, arguments=%s".formatted(
                result.toolName(),
                result.repeatCount(),
                result.argumentsSummary()
        );
    }
}
