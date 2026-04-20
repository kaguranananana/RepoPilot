package com.repopilot.cli.interactive;

import com.repopilot.core.agent.AgentLoopObserver;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.trace.TracePublisher;
import com.repopilot.protocol.session.SessionSummary;
import java.util.List;

/**
 * 交互模式下的单轮运行器。
 * 它负责把已有消息历史继续送进 runtime，
 * 但不负责读取终端输入或打印输出。
 */
public interface InteractiveRuntimeRunner {

    List<ConversationMessage> createInitialHistory(SessionSummary sessionSummary);

    InteractiveTurnResult activateSkill(
            SessionSummary sessionSummary,
            List<ConversationMessage> history,
            String skillName
    );

    InteractiveTurnResult runTurn(
            SessionSummary sessionSummary,
            List<ConversationMessage> history,
            String prompt,
            AgentLoopObserver observer,
            TracePublisher tracePublisher
    );

    default InteractiveTurnResult runTurn(
            SessionSummary sessionSummary,
            List<ConversationMessage> history,
            String prompt,
            AgentLoopObserver observer,
            TracePublisher tracePublisher,
            InteractionMode interactionMode
    ) {
        // 默认方法保持旧实现可用；真正支持模式的 runner 会覆盖这个入口。
        return runTurn(sessionSummary, history, prompt, observer, tracePublisher);
    }
}
