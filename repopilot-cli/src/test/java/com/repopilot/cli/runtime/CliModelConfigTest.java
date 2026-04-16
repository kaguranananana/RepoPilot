package com.repopilot.cli.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CliModelConfigTest {

    @Test
    void shouldUseBootstrapProviderByDefault() {
        CliModelConfig config = CliModelConfig.fromEnvironment(Map.of());

        assertEquals("bootstrap", config.provider());
        assertEquals(null, config.apiKey());
        assertEquals(null, config.baseUrl());
        assertEquals(null, config.modelName());
    }

    @Test
    void shouldResolveDeepSeekConfigFromEnvironment() {
        CliModelConfig config = CliModelConfig.fromEnvironment(Map.of(
                "REPOPILOT_MODEL_PROVIDER", "deepseek",
                "DEEPSEEK_API_KEY", "test-key",
                "DEEPSEEK_BASE_URL", "https://api.deepseek.com",
                "DEEPSEEK_MODEL", "deepseek-chat"
        ));

        assertEquals("deepseek", config.provider());
        assertEquals("test-key", config.apiKey());
        assertEquals("https://api.deepseek.com", config.baseUrl());
        assertEquals("deepseek-chat", config.modelName());
    }

    @Test
    void shouldUseDeepSeekDefaultsWhenOptionalFieldsMissing() {
        CliModelConfig config = CliModelConfig.fromEnvironment(Map.of(
                "REPOPILOT_MODEL_PROVIDER", "deepseek",
                "DEEPSEEK_API_KEY", "test-key"
        ));

        assertEquals("deepseek", config.provider());
        assertEquals("https://api.deepseek.com", config.baseUrl());
        assertEquals("deepseek-chat", config.modelName());
    }

    @Test
    void shouldRejectDeepSeekProviderWithoutApiKey() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> CliModelConfig.fromEnvironment(Map.of("REPOPILOT_MODEL_PROVIDER", "deepseek"))
        );

        assertEquals("DEEPSEEK_API_KEY must not be blank when provider=deepseek.", exception.getMessage());
    }

    @Test
    void shouldRejectUnknownProvider() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> CliModelConfig.fromEnvironment(Map.of("REPOPILOT_MODEL_PROVIDER", "unknown"))
        );

        assertEquals("Unsupported model provider: unknown", exception.getMessage());
    }
}
