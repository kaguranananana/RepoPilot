package com.repopilot.cli.interactive;

import com.repopilot.core.agent.AgentLoopObserver;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.protocol.session.SessionSummary;
import java.util.List;

/**
 * 交互模式下的单轮运行器。
 * 它负责把已有消息历史继续送进 runtime，
 * 但不负责读取终端输入或打印输出。
 */
public interface InteractiveRuntimeRunner {

    List<ConversationMessage> createInitialHistory(SessionSummary sessionSummary);

    InteractiveTurnResult runTurn(
            SessionSummary sessionSummary,
            List<ConversationMessage> history,
            String prompt,
            AgentLoopObserver observer
    );
}
