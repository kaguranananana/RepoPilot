package com.repopilot.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class ContextCompactionPolicyTest {

    @Test
    void shouldTriggerTokenBudgetWhenEstimatedInputExceedsBudget() {
        ContextCompactionPolicy policy = new ContextCompactionPolicy(10, 2, 1, 100, 20, 10);

        ContextCompactionDecision decision = policy.evaluate(
                List.of(new ConversationMessage(MessageRole.USER, "短消息")),
                OptionalInt.of(71)
        );

        assertTrue(decision.shouldCompact());
        assertEquals(ContextCompactionTrigger.TOKEN_BUDGET, decision.trigger().orElseThrow());
        assertEquals(70, decision.maxInputTokens().orElseThrow());
        assertEquals(71, decision.estimatedInputTokens().orElseThrow());
    }

    @Test
    void shouldNotRequestTokenBudgetWhenBudgetIsDisabled() {
        ContextCompactionPolicy policy = new ContextCompactionPolicy(10, 2, 1);

        ContextCompactionDecision decision = policy.evaluate(
                List.of(new ConversationMessage(MessageRole.USER, "短消息")),
                OptionalInt.empty()
        );

        assertFalse(decision.shouldCompact());
        assertTrue(decision.trigger().isEmpty());
        assertTrue(decision.maxInputTokens().isEmpty());
        assertTrue(decision.estimatedInputTokens().isEmpty());
    }
}
