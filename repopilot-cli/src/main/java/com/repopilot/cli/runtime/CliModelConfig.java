package com.repopilot.cli.runtime;

import java.util.Map;

/**
 * CLI 侧模型配置。
 * 当前阶段只支持两种提供方：
 * 1. bootstrap：继续使用本地确定性假模型
 * 2. openai-compatible：走真实 OpenAI 兼容接口
 * 3. anthropic：走真实 Anthropic Messages 接口
 */
public record CliModelConfig(
        String provider,
        String apiKey,
        String baseUrl,
        String modelName
) {

    public static CliModelConfig fromEnvironment(Map<String, String> environment) {
        String provider = normalizeOrDefault(environment.get("REPOPILOT_MODEL_PROVIDER"), "bootstrap");

        return switch (provider) {
            case "bootstrap" -> new CliModelConfig("bootstrap", null, null, null);
            case "openai-compatible" -> new CliModelConfig(
                    "openai-compatible",
                    requireNonBlank(
                            environment.get("OPENAI_COMPATIBLE_API_KEY"),
                            "OPENAI_COMPATIBLE_API_KEY must not be blank when provider=openai-compatible."
                    ),
                    requireNonBlank(
                            environment.get("OPENAI_COMPATIBLE_BASE_URL"),
                            "OPENAI_COMPATIBLE_BASE_URL must not be blank when provider=openai-compatible."
                    ),
                    requireNonBlank(
                            environment.get("OPENAI_COMPATIBLE_MODEL"),
                            "OPENAI_COMPATIBLE_MODEL must not be blank when provider=openai-compatible."
                    )
            );
            case "anthropic" -> new CliModelConfig(
                    "anthropic",
                    requireNonBlank(
                            environment.get("ANTHROPIC_API_KEY"),
                            "ANTHROPIC_API_KEY must not be blank when provider=anthropic."
                    ),
                    requireNonBlank(
                            environment.get("ANTHROPIC_BASE_URL"),
                            "ANTHROPIC_BASE_URL must not be blank when provider=anthropic."
                    ),
                    requireNonBlank(
                            environment.get("ANTHROPIC_MODEL"),
                            "ANTHROPIC_MODEL must not be blank when provider=anthropic."
                    )
            );
            default -> throw new IllegalArgumentException("Unsupported model provider: " + provider);
        };
    }

    private static String normalizeOrDefault(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.strip();
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
