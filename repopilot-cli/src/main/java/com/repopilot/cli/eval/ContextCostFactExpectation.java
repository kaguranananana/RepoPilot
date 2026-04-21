package com.repopilot.cli.eval;

import java.util.Objects;

/**
 * context-cost 场景里的关键事实期望。
 * requiredText 使用确定性字符串匹配，避免引入模型评判或启发式判断。
 */
public record ContextCostFactExpectation(
        String id,
        String description,
        String requiredText
) {

    public ContextCostFactExpectation {
        id = requireNonBlank(id, "id must not be blank.");
        description = requireNonBlank(description, "description must not be blank.");
        requiredText = requireNonBlank(requiredText, "requiredText must not be blank.");
    }

    private static String requireNonBlank(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
