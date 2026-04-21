package com.repopilot.cli.eval;

import com.repopilot.core.agent.AgentLoop;
import com.repopilot.core.agent.AgentLoopObserver;
import com.repopilot.core.agent.AgentLoopRequest;
import com.repopilot.core.agent.AgentLoopResult;
import com.repopilot.core.approval.ToolApprovalHandler;
import com.repopilot.core.context.ContextCompactor;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.permission.WorkspacePermissionPolicy;
import com.repopilot.core.review.DiffReviewService;
import com.repopilot.core.skill.SkillLoader;
import com.repopilot.core.trace.TracePublisher;
import com.repopilot.core.tool.ToolDefinition;
import com.repopilot.core.tool.ToolRegistry;
import com.repopilot.core.tool.builtin.BuiltinToolRegistrar;
import com.repopilot.core.tool.governance.GovernedToolExecutor;
import com.repopilot.protocol.trace.TraceEventType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * context-cost 评测执行器。
 * 它对同一场景分别运行完整历史回放和结构化压缩策略，再输出输入 token 成本对比。
 */
public final class ContextCostEvalRunner {

    private final Path workspaceRoot;
    private final Clock clock;
    private final ModelInputTokenEstimator tokenEstimator;

    public ContextCostEvalRunner(Path workspaceRoot, Clock clock, ModelInputTokenEstimator tokenEstimator) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null.")
                .toAbsolutePath()
                .normalize();
        this.clock = Objects.requireNonNull(clock, "clock must not be null.");
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator must not be null.");
    }

    public ContextCostEvalResult run(
            ContextCostMeasurementKind measurementKind,
            String tokenEncoding,
            double inputPricePerMillionTokens,
            List<ContextCostScenario> scenarios
    ) {
        ContextCostMeasurementKind safeMeasurementKind =
                Objects.requireNonNull(measurementKind, "measurementKind must not be null.");
        List<ContextCostScenario> safeScenarios = List.copyOf(
                Objects.requireNonNull(scenarios, "scenarios must not be null.")
        );
        if (safeScenarios.isEmpty()) {
            throw new IllegalArgumentException("scenarios must not be empty.");
        }

        List<ContextCostEvalResult.ScenarioComparison> comparisons = new ArrayList<>(safeScenarios.size());
        for (ContextCostScenario scenario : safeScenarios) {
            StrategyRunResult baseline = runScenario(scenario, ContextCostStrategy.NO_COMPACTION, safeMeasurementKind);
            StrategyRunResult candidate =
                    runScenario(scenario, ContextCostStrategy.STRUCTURED_COMPACTION, safeMeasurementKind);
            comparisons.add(compareScenario(scenario, baseline, candidate));
        }

        ContextCostEvalResult.Summary summary = summarize(comparisons, inputPricePerMillionTokens);
        return new ContextCostEvalResult(
                safeMeasurementKind,
                Instant.now(clock),
                tokenEncoding,
                inputPricePerMillionTokens,
                ContextCostStrategy.NO_COMPACTION.name(),
                ContextCostStrategy.STRUCTURED_COMPACTION.name(),
                summary,
                comparisons
        );
    }

    private StrategyRunResult runScenario(
            ContextCostScenario scenario,
            ContextCostStrategy strategy,
            ContextCostMeasurementKind measurementKind
    ) {
        Path scenarioWorkspace = workspaceRoot.resolve(scenario.id()).resolve(strategy.name().toLowerCase())
                .toAbsolutePath()
                .normalize();
        TraceCollector traceCollector = new TraceCollector();

        try {
            resetScenarioWorkspace(scenarioWorkspace);
            scenario.workspaceInitializer().initialize(scenarioWorkspace);

            ToolRegistry toolRegistry = new ToolRegistry();
            BuiltinToolRegistrar.registerAll(
                    toolRegistry,
                    scenarioWorkspace,
                    SkillLoader.createDefault(scenarioWorkspace, scenarioWorkspace.resolve("home"))
            );
            TokenAccountingObserver observer = new TokenAccountingObserver(tokenEstimator, toolRegistry.list());
            GovernedToolExecutor governedToolExecutor = new GovernedToolExecutor(
                    toolRegistry,
                    new WorkspacePermissionPolicy(scenarioWorkspace),
                    new DiffReviewService(scenarioWorkspace),
                    request -> ToolApprovalHandler.ApprovalDecision.approve("context-cost 评测场景固定批准"),
                    List.of(),
                    List.of()
            );

            AgentLoopResult result = new AgentLoop(
                    governedToolExecutor,
                    observer,
                    traceCollector,
                    new ContextCompactor(scenario.policyFor(strategy)),
                    messages -> tokenEstimator.estimateInputTokens(messages, toolRegistry.list())
            ).run(new AgentLoopRequest(
                    scenario.modelAdapterFactory().create(scenarioWorkspace, strategy),
                    List.of(new ConversationMessage(MessageRole.USER, scenario.prompt())),
                    scenario.maxSteps()
            ));

            scenario.scenarioVerifier().verify(new ContextCostScenario.ScenarioExecution(
                    scenarioWorkspace,
                    result,
                    traceCollector.events()
            ));

            return StrategyRunResult.success(
                    observer.inputTokensFor(measurementKind),
                    observer.peakInputTokensFor(measurementKind),
                    observer.modelCallCount(),
                    traceCollector.compactionCount(),
                    traceCollector.tokenBudgetCompactionCount(),
                    traceCollector.microcompactedToolResultCount(),
                    evaluateFactRetention(scenario.expectedFacts(), observer.compactedPromptMessages())
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "context-cost 场景运行失败: %s/%s, %s".formatted(scenario.id(), strategy, renderError(exception)),
                    exception
            );
        }
    }

    private ContextCostEvalResult.ScenarioComparison compareScenario(
            ContextCostScenario scenario,
            StrategyRunResult baseline,
            StrategyRunResult candidate
    ) {
        return new ContextCostEvalResult.ScenarioComparison(
                scenario.id(),
                scenario.title(),
                baseline.totalInputTokens(),
                candidate.totalInputTokens(),
                reductionRate(baseline.totalInputTokens(), candidate.totalInputTokens()),
                baseline.peakInputTokens(),
                candidate.peakInputTokens(),
                reductionRate(baseline.peakInputTokens(), candidate.peakInputTokens()),
                baseline.modelCalls(),
                candidate.modelCalls(),
                baseline.compactionCount(),
                candidate.compactionCount(),
                baseline.tokenBudgetCompactionCount(),
                candidate.tokenBudgetCompactionCount(),
                baseline.microcompactedToolResultCount(),
                candidate.microcompactedToolResultCount(),
                candidate.factRetention().expectedFactCount(),
                candidate.factRetention().retainedFactCount(),
                candidate.factRetention().retentionRate()
        );
    }

    private ContextCostEvalResult.Summary summarize(
            List<ContextCostEvalResult.ScenarioComparison> comparisons,
            double inputPricePerMillionTokens
    ) {
        int baselineTotal = comparisons.stream()
                .mapToInt(ContextCostEvalResult.ScenarioComparison::baselineInputTokens)
                .sum();
        int candidateTotal = comparisons.stream()
                .mapToInt(ContextCostEvalResult.ScenarioComparison::candidateInputTokens)
                .sum();
        int baselinePeak = comparisons.stream()
                .mapToInt(ContextCostEvalResult.ScenarioComparison::baselinePeakInputTokens)
                .max()
                .orElse(0);
        int candidatePeak = comparisons.stream()
                .mapToInt(ContextCostEvalResult.ScenarioComparison::candidatePeakInputTokens)
                .max()
                .orElse(0);
        double baselineCost = inputCost(baselineTotal, inputPricePerMillionTokens);
        double candidateCost = inputCost(candidateTotal, inputPricePerMillionTokens);
        int expectedFactCount = comparisons.stream()
                .mapToInt(ContextCostEvalResult.ScenarioComparison::expectedFactCount)
                .sum();
        int candidateRetainedFactCount = comparisons.stream()
                .mapToInt(ContextCostEvalResult.ScenarioComparison::candidateRetainedFactCount)
                .sum();

        return new ContextCostEvalResult.Summary(
                comparisons.size(),
                baselineTotal,
                candidateTotal,
                reductionRate(baselineTotal, candidateTotal),
                baselinePeak,
                candidatePeak,
                reductionRate(baselinePeak, candidatePeak),
                baselineCost,
                candidateCost,
                reductionRateFromDouble(baselineCost, candidateCost),
                expectedFactCount,
                candidateRetainedFactCount,
                reductionRate(expectedFactCount, expectedFactCount - candidateRetainedFactCount)
        );
    }

    private FactRetentionResult evaluateFactRetention(
            List<ContextCostFactExpectation> expectedFacts,
            List<List<ConversationMessage>> compactedPromptMessages
    ) {
        List<ContextCostFactExpectation> safeExpectedFacts =
                List.copyOf(Objects.requireNonNull(expectedFacts, "expectedFacts must not be null."));
        if (safeExpectedFacts.isEmpty()) {
            return new FactRetentionResult(0, 0);
        }
        String retainedPromptText = compactedPromptMessages.isEmpty()
                ? ""
                : renderPromptText(compactedPromptMessages.get(compactedPromptMessages.size() - 1));
        int retainedFacts = 0;
        for (ContextCostFactExpectation expectedFact : safeExpectedFacts) {
            if (retainedPromptText.contains(expectedFact.requiredText())) {
                retainedFacts += 1;
            }
        }
        return new FactRetentionResult(safeExpectedFacts.size(), retainedFacts);
    }

    private String renderPromptText(List<ConversationMessage> messages) {
        StringBuilder builder = new StringBuilder();
        for (ConversationMessage message : messages) {
            builder.append(message.role())
                    .append(System.lineSeparator())
                    .append(message.content())
                    .append(System.lineSeparator());
        }
        return builder.toString();
    }

    private void resetScenarioWorkspace(Path scenarioWorkspace) throws IOException {
        if (!scenarioWorkspace.startsWith(workspaceRoot)) {
            throw new IllegalStateException("场景工作区必须位于评估根目录内: " + scenarioWorkspace);
        }

        if (Files.exists(scenarioWorkspace)) {
            try (var stream = Files.walk(scenarioWorkspace)) {
                for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(scenarioWorkspace);
    }

    private double inputCost(int inputTokens, double inputPricePerMillionTokens) {
        if (inputPricePerMillionTokens < 0.0) {
            throw new IllegalArgumentException("inputPricePerMillionTokens must not be negative.");
        }
        return inputTokens / 1_000_000.0 * inputPricePerMillionTokens;
    }

    private double reductionRate(int baseline, int candidate) {
        if (baseline == 0) {
            return 0.0;
        }
        return (baseline - candidate) / (double) baseline;
    }

    private double reductionRateFromDouble(double baseline, double candidate) {
        if (baseline == 0.0) {
            return 0.0;
        }
        return (baseline - candidate) / baseline;
    }

    private String renderError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getName();
        }
        return message.strip();
    }

    private record StrategyRunResult(
            int totalInputTokens,
            int peakInputTokens,
            int modelCalls,
            int compactionCount,
            int tokenBudgetCompactionCount,
            int microcompactedToolResultCount,
            FactRetentionResult factRetention
    ) {

        static StrategyRunResult success(
                List<Integer> inputTokens,
                int peakInputTokens,
                int modelCalls,
                int compactionCount,
                int tokenBudgetCompactionCount,
                int microcompactedToolResultCount,
                FactRetentionResult factRetention
        ) {
            int totalInputTokens = inputTokens.stream().mapToInt(Integer::intValue).sum();
            return new StrategyRunResult(
                    totalInputTokens,
                    peakInputTokens,
                    modelCalls,
                    compactionCount,
                    tokenBudgetCompactionCount,
                    microcompactedToolResultCount,
                    Objects.requireNonNull(factRetention, "factRetention must not be null.")
            );
        }
    }

    private record FactRetentionResult(
            int expectedFactCount,
            int retainedFactCount
    ) {

        private FactRetentionResult {
            if (expectedFactCount < 0) {
                throw new IllegalArgumentException("expectedFactCount must not be negative.");
            }
            if (retainedFactCount < 0) {
                throw new IllegalArgumentException("retainedFactCount must not be negative.");
            }
            if (retainedFactCount > expectedFactCount) {
                throw new IllegalArgumentException("retainedFactCount must not exceed expectedFactCount.");
            }
        }

        private double retentionRate() {
            if (expectedFactCount == 0) {
                return 0.0;
            }
            return retainedFactCount / (double) expectedFactCount;
        }
    }

    private static final class TokenAccountingObserver implements AgentLoopObserver {

        private final ModelInputTokenEstimator tokenEstimator;
        private final List<ToolDefinition> availableTools;
        private final List<Integer> estimatedInputTokens = new ArrayList<>();
        private final List<Integer> realPromptTokens = new ArrayList<>();
        private final List<List<ConversationMessage>> compactedPromptMessages = new ArrayList<>();

        private TokenAccountingObserver(ModelInputTokenEstimator tokenEstimator, List<ToolDefinition> availableTools) {
            this.tokenEstimator = tokenEstimator;
            this.availableTools = List.copyOf(availableTools);
        }

        @Override
        public void onStepStarted(int stepNumber, List<ConversationMessage> messages) {
            int inputTokens = tokenEstimator.estimateInputTokens(messages, availableTools);
            if (inputTokens < 0) {
                throw new IllegalStateException("本地 token 估算结果不能为负数。");
            }
            estimatedInputTokens.add(inputTokens);
            if (containsCompactedContext(messages)) {
                compactedPromptMessages.add(List.copyOf(messages));
            }
        }

        @Override
        public void onModelResponse(int stepNumber, ModelResponse response) {
            response.tokenUsage().ifPresent(usage -> realPromptTokens.add(usage.promptTokens()));
        }

        List<Integer> inputTokensFor(ContextCostMeasurementKind measurementKind) {
            return switch (measurementKind) {
                case ESTIMATED_INPUT -> List.copyOf(estimatedInputTokens);
                case REAL_USAGE -> {
                    if (realPromptTokens.size() != estimatedInputTokens.size()) {
                        throw new IllegalStateException("真实 usage 口径要求每次模型响应都返回 prompt_tokens。");
                    }
                    yield List.copyOf(realPromptTokens);
                }
            };
        }

        int peakInputTokensFor(ContextCostMeasurementKind measurementKind) {
            return inputTokensFor(measurementKind).stream().mapToInt(Integer::intValue).max().orElse(0);
        }

        int modelCallCount() {
            return estimatedInputTokens.size();
        }

        List<List<ConversationMessage>> compactedPromptMessages() {
            return List.copyOf(compactedPromptMessages);
        }

        private boolean containsCompactedContext(List<ConversationMessage> messages) {
            return messages.stream().anyMatch(message ->
                    message.role() == MessageRole.WORKING_MEMORY || message.role() == MessageRole.CONTEXT_SUMMARY
            );
        }
    }

    private static final class TraceCollector implements TracePublisher {

        private final List<TraceEvent> events = new ArrayList<>();

        @Override
        public void publish(TraceEvent event) {
            events.add(Objects.requireNonNull(event, "event must not be null."));
        }

        List<TraceEvent> events() {
            return List.copyOf(events);
        }

        int compactionCount() {
            return (int) events.stream()
                    .filter(event -> event.type() == TraceEventType.CONTEXT_COMPACTION_COMPLETED)
                    .count();
        }

        int tokenBudgetCompactionCount() {
            return (int) events.stream()
                    .filter(event -> event.type() == TraceEventType.CONTEXT_COMPACTION_COMPLETED)
                    .filter(event -> "TOKEN_BUDGET".equals(event.metadata().get("trigger")))
                    .count();
        }

        int microcompactedToolResultCount() {
            return events.stream()
                    .filter(event -> event.type() == TraceEventType.CONTEXT_COMPACTION_COMPLETED)
                    .map(event -> event.metadata().get("microcompactedToolResultCount"))
                    .filter(Objects::nonNull)
                    .mapToInt(Integer::parseInt)
                    .sum();
        }
    }
}
