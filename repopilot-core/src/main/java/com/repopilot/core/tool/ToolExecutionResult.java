package com.repopilot.core.tool;

import com.repopilot.core.model.ConversationMessage;
import java.util.List;
import java.util.Objects;

/**
 * 工具执行结果。
 * 这里显式区分三种结果：
 * 1. SUCCESS：工具成功完成，本轮结果可以直接喂给模型继续推理。
 * 2. RECOVERABLE_ERROR：工具失败，但失败信息仍然适合回注给模型自我修正。
 * 3. FATAL_ERROR：工具失败且必须立即中断主链路，不能伪装成普通 TOOL 消息继续运行。
 */
public record ToolExecutionResult(
        Status status,
        String output,
        List<ConversationMessage> appendedMessages
) {

    public ToolExecutionResult {
        status = Objects.requireNonNull(status, "status must not be null.");
        output = Objects.requireNonNull(output, "output must not be null.");
        appendedMessages = appendedMessages == null ? List.of() : List.copyOf(appendedMessages);
    }

    public ToolExecutionResult(Status status, String output) {
        this(status, output, List.of());
    }

    public static ToolExecutionResult success(String output) {
        return new ToolExecutionResult(Status.SUCCESS, output);
    }

    public static ToolExecutionResult success(String output, List<ConversationMessage> appendedMessages) {
        return new ToolExecutionResult(Status.SUCCESS, output, appendedMessages);
    }

    public static ToolExecutionResult recoverableError(String output) {
        return new ToolExecutionResult(Status.RECOVERABLE_ERROR, output);
    }

    public static ToolExecutionResult recoverableError(String output, List<ConversationMessage> appendedMessages) {
        return new ToolExecutionResult(Status.RECOVERABLE_ERROR, output, appendedMessages);
    }

    public static ToolExecutionResult fatalError(String output) {
        return new ToolExecutionResult(Status.FATAL_ERROR, output);
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public boolean isFatal() {
        return status == Status.FATAL_ERROR;
    }

    public enum Status {
        SUCCESS,
        RECOVERABLE_ERROR,
        FATAL_ERROR
    }
}
