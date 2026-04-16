package com.repopilot.cli.runtime;

import java.util.Map;

/**
 * CLI 侧模型配置。
 * 当前阶段只支持两种提供方：
 * 1. bootstrap：继续使用本地确定性假模型
 * 2. deepseek：走真实 DeepSeek 兼容接口
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
            case "deepseek" -> new CliModelConfig(
                    "deepseek",
                    requireNonBlank(
                            environment.get("DEEPSEEK_API_KEY"),
                            "DEEPSEEK_API_KEY must not be blank when provider=deepseek."
                    ),
                    normalizeOrDefault(environment.get("DEEPSEEK_BASE_URL"), "https://api.deepseek.com"),
                    normalizeOrDefault(environment.get("DEEPSEEK_MODEL"), "deepseek-chat")
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
