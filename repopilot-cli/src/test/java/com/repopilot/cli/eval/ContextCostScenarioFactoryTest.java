package com.repopilot.cli.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.repopilot.cli.runtime.CliModelConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextCostScenarioFactoryTest {

    @Test
    void shouldExposeMultipleRealUsageScenarios() {
        List<ContextCostScenario> scenarios = ContextCostScenarioFactory.defaultRealUsageScenarios(
                new CliModelConfig(
                        "openai-compatible",
                        "test-key",
                        "https://example.com/v1",
                        "test-model"
                )
        );

        assertEquals(
                List.of(
                        "long-file-read",
                        "batch-read",
                        "spec-review-read"
                ),
                scenarios.stream().map(ContextCostScenario::id).toList()
        );
    }
}
