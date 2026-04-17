package com.repopilot.core.prompt;

/**
 * 显式表达 system prompt 的边界。
 * 基础指令与会话指令会拼成稳定的 system prompt，
 * 高频 runtime metadata 则保持为独立上下文块，避免污染稳定前缀。
 */
public record SystemPromptBoundary(
        String baseInstructions,
        String sessionInstructions,
        String runtimeContextBlock
) {

    public SystemPromptBoundary {
        baseInstructions = requireNonBlank(baseInstructions, "Base instructions must not be blank.");
        sessionInstructions = requireNonBlank(sessionInstructions, "Session instructions must not be blank.");
        runtimeContextBlock = runtimeContextBlock == null ? "" : runtimeContextBlock.strip();
    }

    public String systemPrompt() {
        return baseInstructions + System.lineSeparator() + System.lineSeparator() + sessionInstructions;
    }

    public boolean hasRuntimeContextBlock() {
        return !runtimeContextBlock.isBlank();
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
