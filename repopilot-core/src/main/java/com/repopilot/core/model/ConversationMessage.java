package com.repopilot.core.model;

import java.util.List;
import java.util.Objects;

/**
 * 运行时内部统一使用的消息模型。
 * 当前除了 role 和 content 之外，
 * 还显式承载两类 tool-calling 所需元数据：
 * 1. TOOL 消息对应的 `toolCallId`
 * 2. assistant 发起工具调用时的 `toolCalls`
 */
public record ConversationMessage(
        MessageRole role,
        String content,
        String toolCallId,
        List<ToolCall> toolCalls
) {

    public ConversationMessage {
        role = Objects.requireNonNull(role, "role must not be null.");
        content = content == null ? "" : content;
        toolCallId = normalizeOptionalText(toolCallId);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);

        // TOOL 消息必须带上 toolCallId，
        // 这样模型在收到工具结果时才能准确关联到上一条 assistant tool_call。
        if (role == MessageRole.TOOL && toolCallId == null) {
            throw new IllegalArgumentException("TOOL message must contain toolCallId.");
        }

        // 只有 assistant 才能携带 toolCalls，
        // 避免把“模型请求工具调用”和“工具执行结果”混成一类消息。
        if (!toolCalls.isEmpty() && role != MessageRole.ASSISTANT) {
            throw new IllegalArgumentException("Only ASSISTANT message can contain toolCalls.");
        }

        if (toolCallId != null && role != MessageRole.TOOL) {
            throw new IllegalArgumentException("Only TOOL message can contain toolCallId.");
        }
    }

    public ConversationMessage(MessageRole role, String content) {
        this(role, content, null, List.of());
    }

    public static ConversationMessage assistantToolCalls(List<ToolCall> toolCalls) {
        return new ConversationMessage(MessageRole.ASSISTANT, "", null, toolCalls);
    }

    public static ConversationMessage toolResult(String toolCallId, String content) {
        return new ConversationMessage(MessageRole.TOOL, content, toolCallId, List.of());
    }

    private static String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
