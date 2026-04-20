package com.repopilot.cli.eval;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 一次评估运行的结构化结果。
 * 这里同时保留汇总指标和逐场景明细，
 * 让报告既能看总体趋势，也能回到具体失败任务排查。
 */
public record EvalResult(
        EvalScenario.RuntimeKind runtimeKind,
        Instant generatedAt,
        int scenarioCount,
        int toolCallCount,
        int validToolCallCount,
        double toolCallValidRate,
        double taskSuccessRate,
        double avgSteps,
        double avgDurationMillis,
        List<ScenarioResult> scenarioResults
) {

    public EvalResult {
        runtimeKind = Objects.requireNonNull(runtimeKind, "runtimeKind must not be null.");
        generatedAt = Objects.requireNonNull(generatedAt, "generatedAt must not be null.");
        if (scenarioCount <= 0) {
            throw new IllegalArgumentException("scenarioCount must be greater than zero.");
        }
        if (toolCallCount <= 0) {
            throw new IllegalArgumentException("toolCallCount must be greater than zero.");
        }
        if (validToolCallCount < 0 || validToolCallCount > toolCallCount) {
            throw new IllegalArgumentException("validToolCallCount must be within toolCallCount.");
        }
        if (toolCallValidRate < 0.0 || toolCallValidRate > 1.0) {
            throw new IllegalArgumentException("toolCallValidRate must be between 0 and 1.");
        }
        if (taskSuccessRate < 0.0 || taskSuccessRate > 1.0) {
            throw new IllegalArgumentException("taskSuccessRate must be between 0 and 1.");
        }
        if (avgSteps < 0.0) {
            throw new IllegalArgumentException("avgSteps must not be negative.");
        }
        if (avgDurationMillis < 0.0) {
            throw new IllegalArgumentException("avgDurationMillis must not be negative.");
        }
        scenarioResults = List.copyOf(Objects.requireNonNull(scenarioResults, "scenarioResults must not be null."));
        if (scenarioResults.size() != scenarioCount) {
            throw new IllegalArgumentException("scenarioResults size must equal scenarioCount.");
        }
    }

    /**
     * 单个评估场景的运行结果。
     * 失败诊断字段不使用 null，
     * 这样 JSON 报告可以稳定对比，不需要消费方再猜字段是否缺失。
     */
    public record ScenarioResult(
            String scenarioId,
            String title,
            EvalScenario.RuntimeKind runtimeKind,
            boolean success,
            int steps,
            long durationMillis,
            int toolCallCount,
            int validToolCallCount,
            String failureStage,
            String recentToolCall,
            String finalError,
            String recentTraceRef
    ) {

        public ScenarioResult {
            scenarioId = requireNonBlank(scenarioId, "scenarioId must not be blank.");
            title = requireNonBlank(title, "title must not be blank.");
            runtimeKind = Objects.requireNonNull(runtimeKind, "runtimeKind must not be null.");
            if (steps < 0) {
                throw new IllegalArgumentException("steps must not be negative.");
            }
            if (durationMillis < 0) {
                throw new IllegalArgumentException("durationMillis must not be negative.");
            }
            if (toolCallCount < 0) {
                throw new IllegalArgumentException("toolCallCount must not be negative.");
            }
            if (validToolCallCount < 0 || validToolCallCount > toolCallCount) {
                throw new IllegalArgumentException("validToolCallCount must be within toolCallCount.");
            }
            failureStage = normalizeOptional(failureStage);
            recentToolCall = normalizeOptional(recentToolCall);
            finalError = normalizeOptional(finalError);
            recentTraceRef = normalizeOptional(recentTraceRef);
        }

        private static String requireNonBlank(String value, String message) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(message);
            }
            return value.strip();
        }

        private static String normalizeOptional(String value) {
            return value == null ? "" : value.strip();
        }
    }
}
