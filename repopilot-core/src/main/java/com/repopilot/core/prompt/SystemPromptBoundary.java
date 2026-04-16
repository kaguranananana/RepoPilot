package com.repopilot.core.prompt;

/**
 * 显式表达 system prompt 的边界。
 * 静态宪法与动态政策会拼成稳定的 system prompt，
 * 高频 runtime metadata 则保持为独立上下文块，避免污染稳定前缀。
 */
public record SystemPromptBoundary(
        String staticConstitution,
        String dynamicPolicy,
        String runtimeContextBlock
) {

    public SystemPromptBoundary {
        staticConstitution = requireNonBlank(staticConstitution, "Static constitution must not be blank.");
        dynamicPolicy = requireNonBlank(dynamicPolicy, "Dynamic policy must not be blank.");
        runtimeContextBlock = runtimeContextBlock == null ? "" : runtimeContextBlock.strip();
    }

    public String systemPrompt() {
        return staticConstitution + System.lineSeparator() + System.lineSeparator() + dynamicPolicy;
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
