package com.repopilot.cli.eval;

import com.repopilot.core.agent.AgentLoop;
import com.repopilot.core.agent.AgentLoopObserver;
import com.repopilot.core.agent.AgentLoopRequest;
import com.repopilot.core.agent.AgentLoopResult;
import com.repopilot.core.approval.ToolApprovalHandler;
import com.repopilot.core.context.ContextCompactor;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.permission.WorkspacePermissionPolicy;
import com.repopilot.core.review.DiffReviewService;
import com.repopilot.core.skill.SkillLoader;
import com.repopilot.core.trace.TracePublisher;
import com.repopilot.core.tool.ToolDefinition;
import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolRegistry;
import com.repopilot.core.tool.builtin.BuiltinToolRegistrar;
import com.repopilot.core.tool.governance.GovernedToolExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 最小评估执行器。
 * 它复用 core 的 AgentLoop 和工具治理流水线，
 * 只在外围负责场景夹具、指标统计和失败诊断收集。
 */
public final class EvalRunner {

    private final Path workspaceRoot;
    private final Clock clock;

    public EvalRunner(Path workspaceRoot, Clock clock) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null.")
                .toAbsolutePath()
                .normalize();
        this.clock = Objects.requireNonNull(clock, "clock must not be null.");
    }

    public EvalResult run(List<EvalScenario> scenarios) {
        List<EvalScenario> safeScenarios = List.copyOf(
                Objects.requireNonNull(scenarios, "scenarios must not be null.")
        );
        if (safeScenarios.isEmpty()) {
            throw new IllegalArgumentException("scenarios must not be empty.");
        }

        EvalScenario.RuntimeKind runtimeKind = requireSingleRuntimeKind(safeScenarios);
        List<EvalResult.ScenarioResult> scenarioResults = new ArrayList<>(safeScenarios.size());

        for (EvalScenario scenario : safeScenarios) {
            scenarioResults.add(runScenario(scenario));
        }

        int scenarioCount = scenarioResults.size();
        int successCount = (int) scenarioResults.stream().filter(EvalResult.ScenarioResult::success).count();
        int toolCallCount = scenarioResults.stream().mapToInt(EvalResult.ScenarioResult::toolCallCount).sum();
        int validToolCallCount = scenarioResults.stream().mapToInt(EvalResult.ScenarioResult::validToolCallCount).sum();
        if (toolCallCount == 0) {
            throw new IllegalStateException("评估任务集没有产生工具调用，无法计算 tool_call_valid_rate。");
        }

        return new EvalResult(
                runtimeKind,
                Instant.now(clock),
                scenarioCount,
                toolCallCount,
                validToolCallCount,
                (double) validToolCallCount / toolCallCount,
                (double) successCount / scenarioCount,
                scenarioResults.stream().mapToInt(EvalResult.ScenarioResult::steps).average().orElseThrow(),
                scenarioResults.stream().mapToLong(EvalResult.ScenarioResult::durationMillis).average().orElseThrow(),
                scenarioResults
        );
    }

    private EvalResult.ScenarioResult runScenario(EvalScenario scenario) {
        Path scenarioWorkspace = workspaceRoot.resolve(scenario.id()).toAbsolutePath().normalize();
        ScenarioObserver observer = new ScenarioObserver();
        ToolCallAccounting accounting = new ToolCallAccounting();
        TraceCollector traceCollector = new TraceCollector();
        Instant startedAt = Instant.now(clock);

        try {
            // 每个场景都先重建自己的工作区，
            // 保证同一组评估任务重复执行时不会受到上一次运行残留影响。
            resetScenarioWorkspace(scenarioWorkspace);
            scenario.workspaceInitializer().initialize(scenarioWorkspace);
        } catch (Exception exception) {
            return buildScenarioResult(
                    scenario,
                    false,
                    observer,
                    accounting,
                    traceCollector,
                    startedAt,
                    "setup",
                    renderError(exception)
            );
        }

        AgentLoopResult result;
        try {
            // 工具注册、权限策略和审批处理都按真实 runtime 组件装配，
            // 这样评估不会绕开主链路，只把审批结果固定为“评估环境允许执行”。
            ToolRegistry toolRegistry = new ToolRegistry();
            BuiltinToolRegistrar.registerAll(
                    toolRegistry,
                    scenarioWorkspace,
                    SkillLoader.createDefault(scenarioWorkspace, scenarioWorkspace.resolve("home"))
            );
            GovernedToolExecutor governedToolExecutor = new GovernedToolExecutor(
                    toolRegistry,
                    new WorkspacePermissionPolicy(scenarioWorkspace),
                    new DiffReviewService(scenarioWorkspace),
                    request -> ToolApprovalHandler.ApprovalDecision.approve("评估场景固定批准"),
                    List.of(accounting::recordValidToolCall),
                    List.of()
            );

            result = new AgentLoop(
                    governedToolExecutor,
                    observer,
                    traceCollector,
                    new ContextCompactor(scenario.contextCompactionPolicy())
            ).run(new AgentLoopRequest(
                    scenario.modelAdapterFactory().create(scenarioWorkspace),
                    List.of(new ConversationMessage(MessageRole.USER, scenario.prompt())),
                    scenario.maxSteps()
            ));
        } catch (Exception exception) {
            return buildScenarioResult(
                    scenario,
                    false,
                    observer,
                    accounting,
                    traceCollector,
                    startedAt,
                    "runtime",
                    renderError(exception)
            );
        }

        try {
            // runtime 成功结束后再执行场景验收，
            // 这样 task_success_rate 统计的是“运行结束 + 结果符合预期”的完整闭环。
            scenario.scenarioVerifier().verify(new EvalScenario.ScenarioExecution(
                    scenarioWorkspace,
                    result,
                    traceCollector.events()
            ));
            return buildScenarioResult(
                    scenario,
                    true,
                    observer,
                    accounting,
                    traceCollector,
                    startedAt,
                    "",
                    ""
            );
        } catch (Exception exception) {
            return buildScenarioResult(
                    scenario,
                    false,
                    observer,
                    accounting,
                    traceCollector,
                    startedAt,
                    "assertion",
                    renderError(exception)
            );
        }
    }

    private EvalResult.ScenarioResult buildScenarioResult(
            EvalScenario scenario,
            boolean success,
            ScenarioObserver observer,
            ToolCallAccounting accounting,
            TraceCollector traceCollector,
            Instant startedAt,
            String failureStage,
            String finalError
    ) {
        long durationMillis = Duration.between(startedAt, Instant.now(clock)).toMillis();
        return new EvalResult.ScenarioResult(
                scenario.id(),
                scenario.title(),
                scenario.runtimeKind(),
                success,
                observer.steps(),
                Math.max(0, durationMillis),
                observer.toolCallCount(),
                accounting.validToolCallCount(),
                success ? "" : failureStage,
                observer.recentToolCall(),
                success ? "" : finalError,
                traceCollector.recentTraceRef()
        );
    }

    private EvalScenario.RuntimeKind requireSingleRuntimeKind(List<EvalScenario> scenarios) {
        EvalScenario.RuntimeKind runtimeKind = scenarios.get(0).runtimeKind();
        boolean mixedRuntimeKind = scenarios.stream().anyMatch(scenario -> scenario.runtimeKind() != runtimeKind);
        if (mixedRuntimeKind) {
            throw new IllegalArgumentException("一次评估运行不能混合 scripted runtime 和真实模型 provider。");
        }
        return runtimeKind;
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

    private String renderError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getName();
        }
        return message.strip();
    }

    private static final class ScenarioObserver implements AgentLoopObserver {

        private int steps;
        private int toolCallCount;
        private String recentToolCall = "";

        @Override
        public void onStepStarted(int stepNumber, List<ConversationMessage> messages) {
            // stepNumber 来自 AgentLoop，
            // 直接记录最大值即可得到本场景实际走过的模型轮次。
            steps = stepNumber;
        }

        @Override
        public void onToolExecutionStarted(int stepNumber, ToolCall toolCall) {
            // 这里统计模型尝试发起的工具调用次数，
            // 后续再由治理层 hook 统计其中通过 schema/registry 校验的调用。
            toolCallCount += 1;
            recentToolCall = toolCall.toolName();
        }

        int steps() {
            return steps;
        }

        int toolCallCount() {
            return toolCallCount;
        }

        String recentToolCall() {
            return recentToolCall;
        }
    }

    private static final class ToolCallAccounting {

        private int validToolCallCount;

        void recordValidToolCall(ToolDefinition toolDefinition, Map<String, String> arguments) {
            // pre-execution hook 只有在工具存在且必填参数校验通过后才会触发，
            // 因此这里记录的是“格式和治理入口有效”的工具调用。
            validToolCallCount += 1;
        }

        int validToolCallCount() {
            return validToolCallCount;
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

        String recentTraceRef() {
            if (events.isEmpty()) {
                return "";
            }

            TraceEvent event = resolveMostUsefulTraceEvent();
            String stepNumber = event.metadata().getOrDefault("stepNumber", "none");
            String toolName = event.metadata().get("toolName");
            if (toolName == null || toolName.isBlank()) {
                return "%s#step=%s".formatted(event.type().name(), stepNumber);
            }
            return "%s#step=%s#tool=%s".formatted(event.type().name(), stepNumber, toolName);
        }

        private TraceEvent resolveMostUsefulTraceEvent() {
            for (int index = events.size() - 1; index >= 0; index--) {
                TraceEvent event = events.get(index);
                // 失败排查最需要定位最近工具结果，
                // 因此优先返回最后一次 TOOL_CALL_COMPLETED，而不是后续 FINAL 模型响应。
                if (event.type().name().equals("TOOL_CALL_COMPLETED")) {
                    return event;
                }
            }
            return events.get(events.size() - 1);
        }
    }
}
