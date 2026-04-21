package com.repopilot.core.context;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 单次上下文压缩判断结果。
 * 它把“是否压缩”和“为什么压缩”放在一起，避免 trace 再反推触发原因。
 */
public record ContextCompactionDecision(
        boolean shouldCompact,
        Optional<ContextCompactionTrigger> trigger,
        OptionalInt estimatedInputTokens,
        OptionalInt maxInputTokens
) {

    public ContextCompactionDecision {
        trigger = Objects.requireNonNull(trigger, "trigger must not be null.");
        estimatedInputTokens = Objects.requireNonNull(
                estimatedInputTokens,
                "estimatedInputTokens must not be null."
        );
        maxInputTokens = Objects.requireNonNull(maxInputTokens, "maxInputTokens must not be null.");
        if (shouldCompact && trigger.isEmpty()) {
            throw new IllegalArgumentException("trigger must be present when shouldCompact is true.");
        }
        if (!shouldCompact && trigger.isPresent()) {
            throw new IllegalArgumentException("trigger must be empty when shouldCompact is false.");
        }
        estimatedInputTokens.ifPresent(value -> requireNonNegative(value, "estimatedInputTokens"));
        maxInputTokens.ifPresent(value -> requireNonNegative(value, "maxInputTokens"));
    }

    public static ContextCompactionDecision skipped(OptionalInt estimatedInputTokens, OptionalInt maxInputTokens) {
        return new ContextCompactionDecision(false, Optional.empty(), estimatedInputTokens, maxInputTokens);
    }

    public static ContextCompactionDecision triggered(
            ContextCompactionTrigger trigger,
            OptionalInt estimatedInputTokens,
            OptionalInt maxInputTokens
    ) {
        return new ContextCompactionDecision(
                true,
                Optional.of(Objects.requireNonNull(trigger, "trigger must not be null.")),
                estimatedInputTokens,
                maxInputTokens
        );
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative.");
        }
    }
}
