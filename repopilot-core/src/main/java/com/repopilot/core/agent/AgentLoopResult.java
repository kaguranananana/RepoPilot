package com.repopilot.core.agent;

import com.repopilot.core.model.ConversationMessage;
import java.util.List;

/**
 * agent 单回合运行结果。
 */
public record AgentLoopResult(
        List<ConversationMessage> messages,
        String finalAnswer
) {

    public AgentLoopResult {
        messages = List.copyOf(messages);
    }
}

