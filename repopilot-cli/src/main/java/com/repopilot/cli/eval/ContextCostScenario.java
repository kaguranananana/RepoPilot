package com.repopilot.cli.eval;

import com.repopilot.core.agent.AgentLoopResult;
import com.repopilot.core.context.ContextCompactionPolicy;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.trace.TracePublisher;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * context-cost 专用评测场景。
 * 同一个场景会分别用 no-compaction 和 structured-compaction 两种策略运行。
 */
public record ContextCostScenario(
        String id,
        String title,
        String prompt,
        int maxSteps,
        ContextCompactionPolicy baselinePolicy,
        ContextCompactionPolicy candidatePolicy,
        WorkspaceInitializer workspaceInitializer,
        ModelAdapterFactory modelAdapterFactory,
        ScenarioVerifier scenarioVerifier,
        List<ContextCostFactExpectation> expectedFacts
) {

    private static final Pattern SCENARIO_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]*");

    public ContextCostScenario {
        id = requireScenarioId(id);
        title = requireNonBlank(title, "title must not be blank.");
        prompt = requireNonBlank(prompt, "prompt must not be blank.");
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be greater than zero.");
        }
        baselinePolicy = Objects.requireNonNull(baselinePolicy, "baselinePolicy must not be null.");
        candidatePolicy = Objects.requireNonNull(candidatePolicy, "candidatePolicy must not be null.");
        workspaceInitializer = Objects.requireNonNull(workspaceInitializer, "workspaceInitializer must not be null.");
        modelAdapterFactory = Objects.requireNonNull(modelAdapterFactory, "modelAdapterFactory must not be null.");
        scenarioVerifier = Objects.requireNonNull(scenarioVerifier, "scenarioVerifier must not be null.");
        expectedFacts = List.copyOf(Objects.requireNonNull(expectedFacts, "expectedFacts must not be null."));
    }

    public ContextCostScenario(
            String id,
            String title,
            String prompt,
            int maxSteps,
            ContextCompactionPolicy baselinePolicy,
            ContextCompactionPolicy candidatePolicy,
            WorkspaceInitializer workspaceInitializer,
            ModelAdapterFactory modelAdapterFactory,
            ScenarioVerifier scenarioVerifier
    ) {
        this(
                id,
                title,
                prompt,
                maxSteps,
                baselinePolicy,
                candidatePolicy,
                workspaceInitializer,
                modelAdapterFactory,
                scenarioVerifier,
                List.of()
        );
    }

    public ContextCompactionPolicy policyFor(ContextCostStrategy strategy) {
        return switch (strategy) {
            case NO_COMPACTION -> baselinePolicy;
            case STRUCTURED_COMPACTION -> candidatePolicy;
        };
    }

    private static String requireScenarioId(String value) {
        String safeValue = requireNonBlank(value, "id must not be blank.");
        if (!SCENARIO_ID_PATTERN.matcher(safeValue).matches()) {
            throw new IllegalArgumentException("id must match [a-z0-9][a-z0-9-]*.");
        }
        return safeValue;
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    @FunctionalInterface
    public interface WorkspaceInitializer {

        void initialize(Path workspaceRoot) throws Exception;
    }

    @FunctionalInterface
    public interface ModelAdapterFactory {

        ModelAdapter create(Path workspaceRoot, ContextCostStrategy strategy) throws Exception;
    }

    @FunctionalInterface
    public interface ScenarioVerifier {

        void verify(ScenarioExecution execution) throws Exception;
    }

    public record ScenarioExecution(
            Path workspaceRoot,
            AgentLoopResult agentLoopResult,
            List<TracePublisher.TraceEvent> traceEvents
    ) {

        public ScenarioExecution {
            workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null.");
            agentLoopResult = Objects.requireNonNull(agentLoopResult, "agentLoopResult must not be null.");
            traceEvents = List.copyOf(Objects.requireNonNull(traceEvents, "traceEvents must not be null."));
        }
    }
}
