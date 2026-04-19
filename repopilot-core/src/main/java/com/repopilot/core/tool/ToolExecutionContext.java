package com.repopilot.core.tool;

import com.repopilot.core.model.ConversationMessage;
import java.util.List;

/**
 * 工具执行上下文。
 * 当前只显式暴露本轮工具执行前可见的消息历史，
 * 让需要读取会话状态的工具在不破坏统一工具协议的前提下获得必要上下文。
 */
public record ToolExecutionContext(
        List<ConversationMessage> messages
) {

    public ToolExecutionContext {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public static ToolExecutionContext empty() {
        return new ToolExecutionContext(List.of());
    }
}
