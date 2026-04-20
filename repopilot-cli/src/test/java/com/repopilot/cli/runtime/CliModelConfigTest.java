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
    void shouldResolveOpenAiCompatibleConfigFromEnvironment() {
        CliModelConfig config = CliModelConfig.fromEnvironment(Map.of(
                "REPOPILOT_MODEL_PROVIDER", "openai-compatible",
                "OPENAI_COMPATIBLE_API_KEY", "test-key",
                "OPENAI_COMPATIBLE_BASE_URL", "https://gateway.example.com/v1",
                "OPENAI_COMPATIBLE_MODEL", "kimi-k2.5"
        ));

        assertEquals("openai-compatible", config.provider());
        assertEquals("test-key", config.apiKey());
        assertEquals("https://gateway.example.com/v1", config.baseUrl());
        assertEquals("kimi-k2.5", config.modelName());
    }

    @Test
    void shouldRejectOpenAiCompatibleProviderWithoutApiKey() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> CliModelConfig.fromEnvironment(Map.of(
                        "REPOPILOT_MODEL_PROVIDER", "openai-compatible",
                        "OPENAI_COMPATIBLE_BASE_URL", "https://gateway.example.com/v1",
                        "OPENAI_COMPATIBLE_MODEL", "kimi-k2.5"
                ))
        );

        assertEquals(
                "OPENAI_COMPATIBLE_API_KEY must not be blank when provider=openai-compatible.",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectOpenAiCompatibleProviderWithoutBaseUrl() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> CliModelConfig.fromEnvironment(Map.of(
                        "REPOPILOT_MODEL_PROVIDER", "openai-compatible",
                        "OPENAI_COMPATIBLE_API_KEY", "test-key",
                        "OPENAI_COMPATIBLE_MODEL", "kimi-k2.5"
                ))
        );

        assertEquals(
                "OPENAI_COMPATIBLE_BASE_URL must not be blank when provider=openai-compatible.",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectOpenAiCompatibleProviderWithoutModel() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> CliModelConfig.fromEnvironment(Map.of(
                        "REPOPILOT_MODEL_PROVIDER", "openai-compatible",
                        "OPENAI_COMPATIBLE_API_KEY", "test-key",
                        "OPENAI_COMPATIBLE_BASE_URL", "https://gateway.example.com/v1"
                ))
        );

        assertEquals(
                "OPENAI_COMPATIBLE_MODEL must not be blank when provider=openai-compatible.",
                exception.getMessage()
        );
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
