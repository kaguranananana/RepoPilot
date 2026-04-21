package com.repopilot.cli.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.cli.runtime.CliModelConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextCostScenarioFactoryTest {

    @TempDir
    Path tempRoot;

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

    @Test
    void shouldExposeMultipleRealUsageScenariosForAnthropicProvider() {
        List<ContextCostScenario> scenarios = ContextCostScenarioFactory.defaultRealUsageScenarios(
                new CliModelConfig(
                        "anthropic",
                        "test-key",
                        "https://example.com",
                        "kimi-k2.6"
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

    @Test
    void shouldUseCompressionSizedBatchReadRealUsageScenario() throws Exception {
        ContextCostScenario scenario = findScenario("batch-read");
        Path workspace = tempRoot.resolve("batch-read");

        scenario.workspaceInitializer().initialize(workspace);

        List<String> requiredFiles = List.of(
                "notes/alpha.md",
                "notes/beta.md",
                "notes/gamma.md",
                "notes/delta.md",
                "notes/epsilon.md",
                "notes/zeta.md"
        );
        assertTrue(requiredFiles.stream().allMatch(file -> scenario.prompt().contains(file)));
        assertTrue(totalCharacters(workspace, requiredFiles) > 45_000);
        assertTrue(scenario.expectedFacts().stream().anyMatch(fact -> fact.requiredText().equals("notes/zeta.md")));
    }

    @Test
    void shouldUseCompressionSizedSpecReviewRealUsageScenario() throws Exception {
        ContextCostScenario scenario = findScenario("spec-review-read");
        Path workspace = tempRoot.resolve("spec-review-read");

        scenario.workspaceInitializer().initialize(workspace);

        List<String> requiredFiles = List.of(
                "specs/runtime.md",
                "specs/context.md",
                "specs/skills.md",
                "specs/tools.md",
                "specs/tracing.md"
        );
        assertTrue(requiredFiles.stream().allMatch(file -> scenario.prompt().contains(file)));
        assertTrue(totalCharacters(workspace, requiredFiles) > 40_000);
        assertTrue(scenario.expectedFacts().stream().anyMatch(fact -> fact.requiredText().equals("specs/tracing.md")));
    }

    private ContextCostScenario findScenario(String scenarioId) {
        return ContextCostScenarioFactory.defaultRealUsageScenarios(new CliModelConfig(
                        "anthropic",
                        "test-key",
                        "https://example.com",
                        "kimi-k2.6"
                ))
                .stream()
                .filter(scenario -> scenario.id().equals(scenarioId))
                .findFirst()
                .orElseThrow();
    }

    private int totalCharacters(Path workspace, List<String> requiredFiles) {
        return requiredFiles.stream()
                .map(workspace::resolve)
                .mapToInt(file -> readText(file).length())
                .sum();
    }

    private String readText(Path file) {
        try {
            return Files.readString(file);
        } catch (Exception exception) {
            throw new IllegalStateException("读取测试 fixture 失败: " + file, exception);
        }
    }
}
