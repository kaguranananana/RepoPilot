package com.repopilot.core.agent;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.ModelAdapter;
import java.util.List;
import java.util.Objects;

/**
 * agent 单回合运行请求。
 */
public record AgentLoopRequest(
        ModelAdapter modelAdapter,
        List<ConversationMessage> messages,
        int maxSteps,
        AgentRunMode runMode,
        int toolCallLoopRepeatThreshold
) {

    // 默认第三次连续重复同一工具调用时中断当前回合。
    public static final int DEFAULT_TOOL_CALL_LOOP_REPEAT_THRESHOLD = 3;

    public AgentLoopRequest(ModelAdapter modelAdapter, List<ConversationMessage> messages, int maxSteps) {
        // 旧构造入口沿用默认阈值，保持调用方必须显式传入 model/messages/maxSteps 的主语义不变。
        this(modelAdapter, messages, maxSteps, AgentRunMode.EXECUTE, DEFAULT_TOOL_CALL_LOOP_REPEAT_THRESHOLD);
    }

    public AgentLoopRequest(
            ModelAdapter modelAdapter,
            List<ConversationMessage> messages,
            int maxSteps,
            AgentRunMode runMode
    ) {
        this(modelAdapter, messages, maxSteps, runMode, DEFAULT_TOOL_CALL_LOOP_REPEAT_THRESHOLD);
    }

    public AgentLoopRequest(
            ModelAdapter modelAdapter,
            List<ConversationMessage> messages,
            int maxSteps,
            int toolCallLoopRepeatThreshold
    ) {
        // 兼容已有显式循环阈值入口，并固定默认运行模式为 EXECUTE。
        this(modelAdapter, messages, maxSteps, AgentRunMode.EXECUTE, toolCallLoopRepeatThreshold);
    }

    public AgentLoopRequest {
        Objects.requireNonNull(modelAdapter, "modelAdapter must not be null.");
        Objects.requireNonNull(runMode, "runMode must not be null.");
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty.");
        }
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be greater than zero.");
        }
        if (toolCallLoopRepeatThreshold < 2) {
            // 阈值为 1 时不存在“重复”这一事实，因此这里直接拒绝无效配置。
            throw new IllegalArgumentException("toolCallLoopRepeatThreshold must be greater than one.");
        }
        messages = List.copyOf(messages);
    }
}
