package com.repopilot.cli.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.cli.runtime.CliModelConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvalScenarioTest {

    @Test
    void shouldCreateDefaultRealModelScenarios() {
        List<EvalScenario> scenarios = EvalScenario.defaultRealModelScenarios(new CliModelConfig(
                "openai-compatible",
                "test-key",
                "https://example.com/v1",
                "test-model"
        ));

        assertEquals(
                List.of("code-search", "file-read", "patch-edit", "command-validation", "search-read-patch-command"),
                scenarios.stream().map(EvalScenario::id).toList()
        );
        assertTrue(scenarios.stream().allMatch(scenario -> scenario.runtimeKind() == EvalScenario.RuntimeKind.REAL_MODEL_PROVIDER));
    }

    @Test
    void shouldCreateDefaultRealModelScenariosForAnthropicProvider() {
        List<EvalScenario> scenarios = EvalScenario.defaultRealModelScenarios(new CliModelConfig(
                "anthropic",
                "test-key",
                "https://example.com",
                "kimi-k2.6"
        ));

        assertEquals(
                List.of("code-search", "file-read", "patch-edit", "command-validation", "search-read-patch-command"),
                scenarios.stream().map(EvalScenario::id).toList()
        );
        assertTrue(scenarios.stream().allMatch(scenario -> scenario.runtimeKind() == EvalScenario.RuntimeKind.REAL_MODEL_PROVIDER));
    }

    @Test
    void shouldRejectNonRealModelConfigWhenBuildingDefaultRealModelScenarios() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> EvalScenario.defaultRealModelScenarios(new CliModelConfig(
                        "bootstrap",
                        null,
                        null,
                        null
                ))
        );

        assertEquals(
                "REAL_MODEL_PROVIDER 评估要求 REPOPILOT_MODEL_PROVIDER 为 openai-compatible 或 anthropic。",
                exception.getMessage()
        );
    }
}
