package com.repopilot.core.agent;

import com.repopilot.core.agent.loop.ToolCallLoopDetectionResult;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.tool.ToolExecutionResult;
import java.util.List;

/**
 * AgentLoop 的可选观察器。
 * 它只负责暴露运行时关键事件，
 * 不参与主链路决策，也不改变工具和模型的执行语义。
 */
public interface AgentLoopObserver {

    static AgentLoopObserver noop() {
        return NoopAgentLoopObserver.INSTANCE;
    }

    default void onStepStarted(int stepNumber, List<ConversationMessage> messages) {
    }

    default void onModelResponse(int stepNumber, ModelResponse response) {
    }

    default void onToolExecutionStarted(int stepNumber, ToolCall toolCall) {
    }

    default void onToolExecutionFinished(int stepNumber, ToolCall toolCall, ToolExecutionResult executionResult) {
    }

    default void onToolMessageAdded(int stepNumber, ToolCall toolCall, ConversationMessage toolMessage) {
    }

    /**
     * 工具调用连续重复达到阈值时通知外层。
     * 观察器只负责展示事实，不改变中断决策。
     */
    default void onToolCallLoopDetected(int stepNumber, ToolCallLoopDetectionResult detectionResult) {
    }

    /**
     * 单例 no-op 观察器。
     * 这里显式给一个稳定实现，
     * 避免每次都创建新的匿名对象。
     */
    final class NoopAgentLoopObserver implements AgentLoopObserver {

        private static final NoopAgentLoopObserver INSTANCE = new NoopAgentLoopObserver();

        private NoopAgentLoopObserver() {
        }
    }
}
