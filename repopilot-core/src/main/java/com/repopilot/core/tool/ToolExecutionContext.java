package com.repopilot.core.tool;

import com.repopilot.core.agent.AgentRunMode;
import com.repopilot.core.model.ConversationMessage;
import java.util.List;
import java.util.Objects;

/**
 * 工具执行上下文。
 * 当前只显式暴露本轮工具执行前可见的消息历史，
 * 让需要读取会话状态的工具在不破坏统一工具协议的前提下获得必要上下文。
 */
public record ToolExecutionContext(
        List<ConversationMessage> messages,
        AgentRunMode runMode
) {

    public ToolExecutionContext(List<ConversationMessage> messages) {
        // 旧工具执行入口默认沿用 EXECUTE，避免无模式调用被误判为只读阶段。
        this(messages, AgentRunMode.EXECUTE);
    }

    public ToolExecutionContext {
        messages = messages == null ? List.of() : List.copyOf(messages);
        runMode = Objects.requireNonNull(runMode, "runMode must not be null.");
    }

    public static ToolExecutionContext empty() {
        return new ToolExecutionContext(List.of(), AgentRunMode.EXECUTE);
    }
}
