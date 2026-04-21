package com.repopilot.core.model;

/**
 * 单次模型调用返回的 token 用量。
 * promptTokens 对应输入上下文，completionTokens 对应模型输出，totalTokens 对应 provider 返回的总量。
 */
public record TokenUsage(
        int promptTokens,
        int completionTokens,
        int totalTokens
) {

    public TokenUsage {
        if (promptTokens < 0) {
            throw new IllegalArgumentException("promptTokens must not be negative.");
        }
        if (completionTokens < 0) {
            throw new IllegalArgumentException("completionTokens must not be negative.");
        }
        if (totalTokens < 0) {
            throw new IllegalArgumentException("totalTokens must not be negative.");
        }
        if (totalTokens < promptTokens + completionTokens) {
            throw new IllegalArgumentException("totalTokens must cover promptTokens and completionTokens.");
        }
    }
}
