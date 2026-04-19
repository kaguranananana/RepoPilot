package com.repopilot.core.context;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import java.util.List;

/**
 * 上下文压缩策略。
 * 一期只保留显式阈值，不引入任何模糊启发式判断。
 */
public record ContextCompactionPolicy(
        int maxHighFidelityMessages,
        int retainedHighFidelityMessages,
        int maxRecentToolResults
) {

    public ContextCompactionPolicy {
        if (maxHighFidelityMessages <= 0) {
            throw new IllegalArgumentException("maxHighFidelityMessages must be greater than zero.");
        }
        if (retainedHighFidelityMessages <= 0) {
            throw new IllegalArgumentException("retainedHighFidelityMessages must be greater than zero.");
        }
        if (retainedHighFidelityMessages > maxHighFidelityMessages) {
            throw new IllegalArgumentException(
                    "retainedHighFidelityMessages must not exceed maxHighFidelityMessages."
            );
        }
        if (maxRecentToolResults <= 0) {
            throw new IllegalArgumentException("maxRecentToolResults must be greater than zero.");
        }
    }

    public static ContextCompactionPolicy defaultPolicy() {
        return new ContextCompactionPolicy(8, 4, 3);
    }

    public boolean shouldCompact(List<ConversationMessage> messages) {
        return countHighFidelityMessages(messages) > maxHighFidelityMessages;
    }

    public int countHighFidelityMessages(List<ConversationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        return (int) messages.stream()
                .filter(this::isHighFidelityMessage)
                .count();
    }

    private boolean isHighFidelityMessage(ConversationMessage message) {
        MessageRole role = message.role();
        return role != MessageRole.SYSTEM
                && role != MessageRole.WORKING_MEMORY
                && role != MessageRole.CONTEXT_SUMMARY;
    }
}
