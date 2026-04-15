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
        int maxSteps
) {

    public AgentLoopRequest {
        Objects.requireNonNull(modelAdapter, "modelAdapter must not be null.");
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty.");
        }
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be greater than zero.");
        }
        messages = List.copyOf(messages);
    }
}

