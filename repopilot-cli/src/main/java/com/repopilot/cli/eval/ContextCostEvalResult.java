package com.repopilot.cli.eval;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * context-cost A/B 评测结果。
 */
public record ContextCostEvalResult(
        ContextCostMeasurementKind measurementKind,
        Instant generatedAt,
        String tokenEncoding,
        double inputPricePerMillionTokens,
        String baselineStrategy,
        String candidateStrategy,
        Summary summary,
        List<ScenarioComparison> scenarioComparisons
) {

    public ContextCostEvalResult {
        measurementKind = Objects.requireNonNull(measurementKind, "measurementKind must not be null.");
        generatedAt = Objects.requireNonNull(generatedAt, "generatedAt must not be null.");
        tokenEncoding = requireNonBlank(tokenEncoding, "tokenEncoding must not be blank.");
        if (inputPricePerMillionTokens < 0.0) {
            throw new IllegalArgumentException("inputPricePerMillionTokens must not be negative.");
        }
        baselineStrategy = requireNonBlank(baselineStrategy, "baselineStrategy must not be blank.");
        candidateStrategy = requireNonBlank(candidateStrategy, "candidateStrategy must not be blank.");
        summary = Objects.requireNonNull(summary, "summary must not be null.");
        scenarioComparisons =
                List.copyOf(Objects.requireNonNull(scenarioComparisons, "scenarioComparisons must not be null."));
        if (scenarioComparisons.size() != summary.scenarioCount()) {
            throw new IllegalArgumentException("scenarioComparisons size must equal summary scenarioCount.");
        }
    }

    public record Summary(
            int scenarioCount,
            int baselineTotalInputTokens,
            int candidateTotalInputTokens,
            double inputTokenReductionRate,
            int baselinePeakInputTokens,
            int candidatePeakInputTokens,
            double peakInputTokenReductionRate,
            double baselineInputCost,
            double candidateInputCost,
            double inputCostReductionRate,
            int expectedFactCount,
            int candidateRetainedFactCount,
            double candidateFactRetentionRate
    ) {

        public Summary {
            if (scenarioCount <= 0) {
                throw new IllegalArgumentException("scenarioCount must be greater than zero.");
            }
            requireNonNegative(baselineTotalInputTokens, "baselineTotalInputTokens");
            requireNonNegative(candidateTotalInputTokens, "candidateTotalInputTokens");
            requireFinite(inputTokenReductionRate, "inputTokenReductionRate");
            requireNonNegative(baselinePeakInputTokens, "baselinePeakInputTokens");
            requireNonNegative(candidatePeakInputTokens, "candidatePeakInputTokens");
            requireFinite(peakInputTokenReductionRate, "peakInputTokenReductionRate");
            if (baselineInputCost < 0.0 || candidateInputCost < 0.0) {
                throw new IllegalArgumentException("cost metrics must not be negative.");
            }
            requireFinite(inputCostReductionRate, "inputCostReductionRate");
            requireNonNegative(expectedFactCount, "expectedFactCount");
            requireNonNegative(candidateRetainedFactCount, "candidateRetainedFactCount");
            if (candidateRetainedFactCount > expectedFactCount) {
                throw new IllegalArgumentException("candidateRetainedFactCount must not exceed expectedFactCount.");
            }
            requireFinite(candidateFactRetentionRate, "candidateFactRetentionRate");
        }
    }

    public record ScenarioComparison(
            String scenarioId,
            String title,
            int baselineInputTokens,
            int candidateInputTokens,
            double inputTokenReductionRate,
            int baselinePeakInputTokens,
            int candidatePeakInputTokens,
            double peakInputTokenReductionRate,
            int baselineModelCalls,
            int candidateModelCalls,
            int baselineCompactionCount,
            int candidateCompactionCount,
            int baselineTokenBudgetCompactionCount,
            int candidateTokenBudgetCompactionCount,
            int baselineMicrocompactedToolResultCount,
            int candidateMicrocompactedToolResultCount,
            int expectedFactCount,
            int candidateRetainedFactCount,
            double candidateFactRetentionRate
    ) {

        public ScenarioComparison {
            scenarioId = requireNonBlank(scenarioId, "scenarioId must not be blank.");
            title = requireNonBlank(title, "title must not be blank.");
            requireNonNegative(baselineInputTokens, "baselineInputTokens");
            requireNonNegative(candidateInputTokens, "candidateInputTokens");
            requireFinite(inputTokenReductionRate, "inputTokenReductionRate");
            requireNonNegative(baselinePeakInputTokens, "baselinePeakInputTokens");
            requireNonNegative(candidatePeakInputTokens, "candidatePeakInputTokens");
            requireFinite(peakInputTokenReductionRate, "peakInputTokenReductionRate");
            requireNonNegative(baselineModelCalls, "baselineModelCalls");
            requireNonNegative(candidateModelCalls, "candidateModelCalls");
            requireNonNegative(baselineCompactionCount, "baselineCompactionCount");
            requireNonNegative(candidateCompactionCount, "candidateCompactionCount");
            requireNonNegative(baselineTokenBudgetCompactionCount, "baselineTokenBudgetCompactionCount");
            requireNonNegative(candidateTokenBudgetCompactionCount, "candidateTokenBudgetCompactionCount");
            requireNonNegative(baselineMicrocompactedToolResultCount, "baselineMicrocompactedToolResultCount");
            requireNonNegative(candidateMicrocompactedToolResultCount, "candidateMicrocompactedToolResultCount");
            requireNonNegative(expectedFactCount, "expectedFactCount");
            requireNonNegative(candidateRetainedFactCount, "candidateRetainedFactCount");
            if (candidateRetainedFactCount > expectedFactCount) {
                throw new IllegalArgumentException("candidateRetainedFactCount must not exceed expectedFactCount.");
            }
            requireFinite(candidateFactRetentionRate, "candidateFactRetentionRate");
        }
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative.");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite.");
        }
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
