package com.repopilot.cli.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.context.ContextCompactionPolicy;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.TokenUsage;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import com.repopilot.core.tool.ToolDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextCostEvalRunnerTest {

    @TempDir
    Path tempRoot;

    @Test
    void shouldCompareNoCompactionAndStructuredCompactionByEstimatedInputTokens() {
        ContextCostEvalRunner runner = new ContextCostEvalRunner(
                tempRoot.resolve("estimated-workspaces"),
                Clock.fixed(Instant.parse("2026-04-21T01:00:00Z"), ZoneOffset.UTC),
                (messages, tools) -> messages.stream().mapToInt(message -> message.content().length()).sum()
                        + tools.size() * 10
        );

        ContextCostEvalResult result = runner.run(
                ContextCostMeasurementKind.ESTIMATED_INPUT,
                "test-estimator",
                0.0,
                List.of(longReadScenario(false))
        );

        assertEquals(ContextCostMeasurementKind.ESTIMATED_INPUT, result.measurementKind());
        assertEquals("NO_COMPACTION", result.baselineStrategy());
        assertEquals("STRUCTURED_COMPACTION", result.candidateStrategy());
        assertEquals(1, result.summary().scenarioCount());
        assertTrue(
                result.summary().baselineTotalInputTokens() > result.summary().candidateTotalInputTokens(),
                () -> result.summary().toString()
        );
        assertTrue(result.summary().inputTokenReductionRate() > 0.0);
        assertTrue(result.scenarioComparisons().get(0).candidateCompactionCount() > 0);
    }

    @Test
    void shouldUseRealPromptUsageWhenMeasurementKindIsRealUsage() {
        ContextCostEvalRunner runner = new ContextCostEvalRunner(
                tempRoot.resolve("real-usage-workspaces"),
                Clock.fixed(Instant.parse("2026-04-21T01:00:00Z"), ZoneOffset.UTC),
                (messages, tools) -> 999
        );

        ContextCostEvalResult result = runner.run(
                ContextCostMeasurementKind.REAL_USAGE,
                "test-estimator",
                0.0,
                List.of(longReadScenario(true))
        );

        ContextCostEvalResult.ScenarioComparison comparison = result.scenarioComparisons().get(0);
        assertEquals(360, comparison.baselineInputTokens());
        assertEquals(190, comparison.candidateInputTokens());
        assertEquals(360, result.summary().baselineTotalInputTokens());
        assertEquals(190, result.summary().candidateTotalInputTokens());
    }

    @Test
    void shouldFailRealUsageMeasurementWhenModelResponseDoesNotContainUsage() {
        ContextCostEvalRunner runner = new ContextCostEvalRunner(
                tempRoot.resolve("missing-usage-workspaces"),
                Clock.fixed(Instant.parse("2026-04-21T01:00:00Z"), ZoneOffset.UTC),
                (messages, tools) -> 1
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> runner.run(
                        ContextCostMeasurementKind.REAL_USAGE,
                        "test-estimator",
                        0.0,
                        List.of(longReadScenario(false))
                )
        );

        assertTrue(exception.getMessage().contains("prompt_tokens"));
    }

    @Test
    void shouldRecordTokenBudgetAndMicrocompactMetrics() {
        ContextCostEvalRunner runner = new ContextCostEvalRunner(
                tempRoot.resolve("budget-metrics-workspaces"),
                Clock.fixed(Instant.parse("2026-04-21T01:00:00Z"), ZoneOffset.UTC),
                (messages, tools) -> messages.stream().mapToInt(message -> message.content().length()).sum()
        );

        ContextCostEvalResult result = runner.run(
                ContextCostMeasurementKind.ESTIMATED_INPUT,
                "test-estimator",
                0.0,
                List.of(twoReadTokenBudgetScenario())
        );

        ContextCostEvalResult.ScenarioComparison comparison = result.scenarioComparisons().get(0);
        assertEquals(2, comparison.candidateTokenBudgetCompactionCount());
        assertEquals(1, comparison.candidateMicrocompactedToolResultCount());
        assertEquals(0, comparison.baselineTokenBudgetCompactionCount());
        assertEquals(0, comparison.baselineMicrocompactedToolResultCount());
    }

    @Test
    void shouldCalculateCandidateFactRetentionFromCompactedPrompt() {
        ContextCostEvalRunner runner = new ContextCostEvalRunner(
                tempRoot.resolve("fact-retention-workspaces"),
                Clock.fixed(Instant.parse("2026-04-21T01:00:00Z"), ZoneOffset.UTC),
                (messages, tools) -> messages.stream().mapToInt(message -> message.content().length()).sum()
        );

        ContextCostEvalResult result = runner.run(
                ContextCostMeasurementKind.ESTIMATED_INPUT,
                "test-estimator",
                0.0,
                List.of(twoReadTokenBudgetScenario())
        );

        ContextCostEvalResult.ScenarioComparison comparison = result.scenarioComparisons().get(0);
        assertEquals(3, comparison.expectedFactCount());
        assertEquals(3, comparison.candidateRetainedFactCount());
        assertEquals(1.0, comparison.candidateFactRetentionRate());
        assertEquals(3, result.summary().expectedFactCount());
        assertEquals(3, result.summary().candidateRetainedFactCount());
        assertEquals(1.0, result.summary().candidateFactRetentionRate());
    }

    private ContextCostScenario longReadScenario(boolean includeUsage) {
        return new ContextCostScenario(
                "long-read",
                "长文件读取",
                "连续读取多个文件后汇报。",
                10,
                new ContextCompactionPolicy(10_000, 9_999, 10),
                new ContextCompactionPolicy(4, 1, 1),
                workspace -> {
                    Files.createDirectories(workspace.resolve("notes"));
                    Files.writeString(workspace.resolve("notes/a.txt"), "A".repeat(3_000));
                    Files.writeString(workspace.resolve("notes/b.txt"), "B".repeat(3_000));
                    Files.writeString(workspace.resolve("notes/c.txt"), "C".repeat(3_000));
                    Files.writeString(workspace.resolve("notes/d.txt"), "D".repeat(3_000));
                    Files.writeString(workspace.resolve("notes/e.txt"), "E".repeat(3_000));
                    Files.writeString(workspace.resolve("notes/f.txt"), "F".repeat(3_000));
                },
                (workspace, strategy) -> scriptedModel(strategy == ContextCostStrategy.NO_COMPACTION
                        ? responses(includeUsage, 100, 120, 140, 0, 0, 0)
                        : responses(includeUsage, 60, 70, 60, 0, 0, 0)),
                execution -> assertTrue(execution.agentLoopResult().finalAnswer().contains("完成"))
        );
    }

    private ContextCostScenario twoReadTokenBudgetScenario() {
        return new ContextCostScenario(
                "two-read-budget",
                "两次读取触发 token budget",
                "连续读取两个文件后汇报。",
                5,
                new ContextCompactionPolicy(10_000, 9_999, 10),
                new ContextCompactionPolicy(100, 100, 1, 100, 20, 20),
                workspace -> {
                    Files.createDirectories(workspace.resolve("notes"));
                    Files.writeString(workspace.resolve("notes/a.txt"), "A".repeat(3_000));
                    Files.writeString(workspace.resolve("notes/b.txt"), "B".repeat(3_000));
                },
                (workspace, strategy) -> scriptedModel(List.of(
                        tool("call-a", "read_file", Map.of("path", "notes/a.txt")),
                        tool("call-b", "read_file", Map.of("path", "notes/b.txt")),
                        new FinalModelResponse("完成")
                )),
                execution -> assertTrue(execution.agentLoopResult().finalAnswer().contains("完成")),
                List.of(
                        new ContextCostFactExpectation("goal", "用户目标", "连续读取两个文件后汇报。"),
                        new ContextCostFactExpectation("file-a", "已读文件 A", "notes/a.txt"),
                        new ContextCostFactExpectation("file-b", "已读文件 B", "notes/b.txt")
                )
        );
    }

    private static List<ModelResponse> responses(
            boolean includeUsage,
            int firstPromptTokens,
            int secondPromptTokens,
            int thirdPromptTokens,
            int fourthPromptTokens,
            int fifthPromptTokens,
            int sixthPromptTokens
    ) {
        if (includeUsage) {
            return List.of(
                    tool("call-a", "read_file", Map.of("path", "notes/a.txt"), firstPromptTokens),
                    tool("call-b", "read_file", Map.of("path", "notes/b.txt"), secondPromptTokens),
                    tool("call-c", "read_file", Map.of("path", "notes/c.txt"), thirdPromptTokens),
                    tool("call-d", "read_file", Map.of("path", "notes/d.txt"), fourthPromptTokens),
                    tool("call-e", "read_file", Map.of("path", "notes/e.txt"), fifthPromptTokens),
                    tool("call-f", "read_file", Map.of("path", "notes/f.txt"), sixthPromptTokens),
                    new FinalModelResponse("完成", new TokenUsage(0, 1, 1))
            );
        }
        return List.of(
                tool("call-a", "read_file", Map.of("path", "notes/a.txt")),
                tool("call-b", "read_file", Map.of("path", "notes/b.txt")),
                tool("call-c", "read_file", Map.of("path", "notes/c.txt")),
                tool("call-d", "read_file", Map.of("path", "notes/d.txt")),
                tool("call-e", "read_file", Map.of("path", "notes/e.txt")),
                tool("call-f", "read_file", Map.of("path", "notes/f.txt")),
                new FinalModelResponse("完成")
        );
    }

    private static ToolCallModelResponse tool(String id, String toolName, Map<String, String> arguments) {
        return new ToolCallModelResponse(List.of(new ToolCall(id, toolName, arguments)));
    }

    private static ToolCallModelResponse tool(
            String id,
            String toolName,
            Map<String, String> arguments,
            int promptTokens
    ) {
        return new ToolCallModelResponse(
                List.of(new ToolCall(id, toolName, arguments)),
                new TokenUsage(promptTokens, 1, promptTokens + 1)
        );
    }

    private static ModelAdapter scriptedModel(List<ModelResponse> responses) {
        return new ScriptedModelAdapter(responses);
    }

    private static final class ScriptedModelAdapter implements ModelAdapter {

        private final List<ModelResponse> responses;
        private int cursor;

        private ScriptedModelAdapter(List<ModelResponse> responses) {
            this.responses = responses;
        }

        @Override
        public ModelResponse next(List<com.repopilot.core.model.ConversationMessage> messages) {
            ModelResponse response = responses.get(cursor);
            cursor += 1;
            return response;
        }
    }
}
