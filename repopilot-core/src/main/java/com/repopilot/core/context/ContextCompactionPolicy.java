package com.repopilot.core.context;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import java.util.List;
import java.util.OptionalInt;

/**
 * 上下文压缩策略。
 * 一期只保留显式阈值，不引入任何模糊启发式判断。
 */
public record ContextCompactionPolicy(
        int maxHighFidelityMessages,
        int retainedHighFidelityMessages,
        int maxRecentToolResults,
        int contextWindowTokens,
        int reservedOutputTokens,
        int safetyBufferTokens
) {

    public ContextCompactionPolicy(int maxHighFidelityMessages, int retainedHighFidelityMessages, int maxRecentToolResults) {
        this(maxHighFidelityMessages, retainedHighFidelityMessages, maxRecentToolResults, 0, 0, 0);
    }

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
        validateTokenBudget(contextWindowTokens, reservedOutputTokens, safetyBufferTokens);
    }

    public static ContextCompactionPolicy defaultPolicy() {
        return new ContextCompactionPolicy(8, 4, 3);
    }

    public boolean shouldCompact(List<ConversationMessage> messages) {
        return evaluate(messages, OptionalInt.empty()).shouldCompact();
    }

    public ContextCompactionDecision evaluate(
            List<ConversationMessage> messages,
            OptionalInt estimatedInputTokens
    ) {
        OptionalInt safeEstimatedInputTokens = estimatedInputTokens == null ? OptionalInt.empty() : estimatedInputTokens;
        OptionalInt maxInputTokens = maxInputTokens();
        if (maxInputTokens.isPresent()) {
            if (safeEstimatedInputTokens.isEmpty()) {
                throw new IllegalStateException("token budget 压缩要求显式传入 estimatedInputTokens。");
            }
            if (safeEstimatedInputTokens.getAsInt() > maxInputTokens.getAsInt()) {
                return ContextCompactionDecision.triggered(
                        ContextCompactionTrigger.TOKEN_BUDGET,
                        safeEstimatedInputTokens,
                        maxInputTokens
                );
            }
        }
        if (countHighFidelityMessages(messages) > maxHighFidelityMessages) {
            return ContextCompactionDecision.triggered(
                    ContextCompactionTrigger.HIGH_FIDELITY_MESSAGE_LIMIT,
                    safeEstimatedInputTokens,
                    maxInputTokens
            );
        }
        return ContextCompactionDecision.skipped(safeEstimatedInputTokens, maxInputTokens);
    }

    public boolean isTokenBudgetEnabled() {
        return contextWindowTokens > 0;
    }

    public OptionalInt maxInputTokens() {
        if (!isTokenBudgetEnabled()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(contextWindowTokens - reservedOutputTokens - safetyBufferTokens);
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

    private void validateTokenBudget(int contextWindowTokens, int reservedOutputTokens, int safetyBufferTokens) {
        boolean tokenBudgetDisabled = contextWindowTokens == 0
                && reservedOutputTokens == 0
                && safetyBufferTokens == 0;
        if (tokenBudgetDisabled) {
            return;
        }
        if (contextWindowTokens <= 0) {
            throw new IllegalArgumentException("contextWindowTokens must be greater than zero when token budget is enabled.");
        }
        if (reservedOutputTokens <= 0) {
            throw new IllegalArgumentException("reservedOutputTokens must be greater than zero when token budget is enabled.");
        }
        if (safetyBufferTokens <= 0) {
            throw new IllegalArgumentException("safetyBufferTokens must be greater than zero when token budget is enabled.");
        }
        if (reservedOutputTokens + safetyBufferTokens >= contextWindowTokens) {
            throw new IllegalArgumentException(
                    "reservedOutputTokens plus safetyBufferTokens must be less than contextWindowTokens."
            );
        }
    }
}
