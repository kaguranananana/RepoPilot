package com.repopilot.cli.eval;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.cli.runtime.JTokkitModelInputTokenEstimator;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import java.util.List;
import org.junit.jupiter.api.Test;

class JTokkitModelInputTokenEstimatorTest {

    @Test
    void shouldEstimateInputTokensWithExplicitEncoding() {
        JTokkitModelInputTokenEstimator estimator = new JTokkitModelInputTokenEstimator("cl100k_base");

        int shortPromptTokens = estimator.estimateInputTokens(
                List.of(new ConversationMessage(MessageRole.USER, "读取 README.md")),
                List.of()
        );
        int longPromptTokens = estimator.estimateInputTokens(
                List.of(new ConversationMessage(MessageRole.USER, "读取 README.md\n" + "长上下文".repeat(100))),
                List.of()
        );

        assertTrue(shortPromptTokens > 0);
        assertTrue(longPromptTokens > shortPromptTokens);
    }

    @Test
    void shouldRejectUnsupportedEncodingName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new JTokkitModelInputTokenEstimator("unknown_encoding")
        );
    }
}
