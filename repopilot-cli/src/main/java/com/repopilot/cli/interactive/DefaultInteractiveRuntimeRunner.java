package com.repopilot.cli.interactive;

import com.repopilot.cli.runtime.CliRuntimeBootstrap;
import com.repopilot.core.agent.AgentLoop;
import com.repopilot.core.agent.AgentLoopObserver;
import com.repopilot.core.agent.AgentLoopRequest;
import com.repopilot.core.agent.AgentLoopResult;
import com.repopilot.core.approval.ToolApprovalHandler;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.prompt.DynamicPromptContext;
import com.repopilot.core.prompt.SystemPromptBoundary;
import com.repopilot.core.prompt.SystemPromptBuilder;
import com.repopilot.core.permission.WorkspacePermissionPolicy;
import com.repopilot.core.review.DiffReviewService;
import com.repopilot.core.skill.ActivatedSkillSet;
import com.repopilot.core.skill.SkillActivationResult;
import com.repopilot.core.skill.SkillActivationService;
import com.repopilot.core.skill.SkillLoader;
import com.repopilot.core.trace.TracePublisher;
import com.repopilot.core.tool.ToolDefinition;
import com.repopilot.core.tool.ToolRegistry;
import com.repopilot.core.tool.builtin.BuiltinToolRegistrar;
import com.repopilot.core.tool.governance.GovernedToolExecutor;
import com.repopilot.protocol.session.SessionSummary;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 默认交互式运行器。
 * 它复用现有 CLI runtime 的 system prompt 和工具装配策略，
 * 但把消息历史改成可跨多轮持续复用。
 */
public final class DefaultInteractiveRuntimeRunner implements InteractiveRuntimeRunner {

    private final Clock clock;
    private final SystemPromptBuilder systemPromptBuilder;
    private final CliRuntimeBootstrap.ModelAdapterFactory modelAdapterFactory;
    private final Path workspaceRoot;
    private final int maxSteps;
    private final ToolApprovalHandler toolApprovalHandler;
    private final SkillLoader skillLoader;
    private final SkillActivationService skillActivationService;

    public DefaultInteractiveRuntimeRunner(
            Clock clock,
            CliRuntimeBootstrap.ModelAdapterFactory modelAdapterFactory,
            int maxSteps
    ) {
        this(
                clock,
                new SystemPromptBuilder(),
                modelAdapterFactory,
                Path.of("").toAbsolutePath().normalize(),
                maxSteps,
                ToolApprovalHandler.denyAll(),
                SkillLoader.createDefault(
                        Path.of("").toAbsolutePath().normalize(),
                        resolveUserHome()
                )
        );
    }

    DefaultInteractiveRuntimeRunner(
            Clock clock,
            CliRuntimeBootstrap.ModelAdapterFactory modelAdapterFactory,
            Path workspaceRoot,
            int maxSteps,
            ToolApprovalHandler toolApprovalHandler
    ) {
        this(
                clock,
                new SystemPromptBuilder(),
                modelAdapterFactory,
                workspaceRoot,
                maxSteps,
                toolApprovalHandler,
                SkillLoader.createDefault(workspaceRoot, resolveUserHome())
        );
    }

    DefaultInteractiveRuntimeRunner(
            Clock clock,
            SystemPromptBuilder systemPromptBuilder,
            CliRuntimeBootstrap.ModelAdapterFactory modelAdapterFactory,
            Path workspaceRoot,
            int maxSteps,
            ToolApprovalHandler toolApprovalHandler,
            SkillLoader skillLoader
    ) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null.");
        this.systemPromptBuilder = Objects.requireNonNull(systemPromptBuilder, "systemPromptBuilder must not be null.");
        this.modelAdapterFactory = Objects.requireNonNull(modelAdapterFactory, "modelAdapterFactory must not be null.");
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null.")
                .toAbsolutePath()
                .normalize();
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be greater than zero.");
        }
        this.maxSteps = maxSteps;
        this.toolApprovalHandler = Objects.requireNonNull(toolApprovalHandler, "toolApprovalHandler must not be null.");
        this.skillLoader = Objects.requireNonNull(skillLoader, "skillLoader must not be null.");
        this.skillActivationService = new SkillActivationService(this.skillLoader);
    }

    @Override
    public List<ConversationMessage> createInitialHistory(SessionSummary sessionSummary) {
        Objects.requireNonNull(sessionSummary, "sessionSummary must not be null.");

        ToolRegistry toolRegistry = createToolRegistry();
        return rebuildPromptBoundary(sessionSummary, List.of(), toolRegistry);
    }

    @Override
    public InteractiveTurnResult runTurn(
            SessionSummary sessionSummary,
            List<ConversationMessage> history,
            String prompt,
            AgentLoopObserver observer,
            TracePublisher tracePublisher
    ) {
        Objects.requireNonNull(sessionSummary, "sessionSummary must not be null.");
        Objects.requireNonNull(history, "history must not be null.");
        Objects.requireNonNull(observer, "observer must not be null.");
        Objects.requireNonNull(tracePublisher, "tracePublisher must not be null.");
        String safePrompt = requireNonBlank(prompt, "prompt must not be blank.");

        ToolRegistry toolRegistry = createToolRegistry();
        List<ConversationMessage> refreshedHistory = rebuildPromptBoundary(sessionSummary, history, toolRegistry);
        List<ConversationMessage> messages = new ArrayList<>(refreshedHistory);

        // 每次新输入都只追加一条新的 USER 消息，
        // 前面的 SYSTEM / USER / ASSISTANT / TOOL 历史保持原样继续复用。
        messages.add(new ConversationMessage(MessageRole.USER, safePrompt));

        GovernedToolExecutor governedToolExecutor = new GovernedToolExecutor(
                toolRegistry,
                new WorkspacePermissionPolicy(workspaceRoot),
                new DiffReviewService(workspaceRoot),
                toolApprovalHandler
        );
        AgentLoop agentLoop = new AgentLoop(governedToolExecutor, observer, tracePublisher);
        AgentLoopResult result = agentLoop.run(new AgentLoopRequest(
                modelAdapterFactory.create(sessionSummary, resolveEffectiveTools(refreshedHistory, toolRegistry)),
                messages,
                maxSteps
        ));

        return new InteractiveTurnResult(result.messages(), result.finalAnswer());
    }

    @Override
    public InteractiveTurnResult activateSkill(
            SessionSummary sessionSummary,
            List<ConversationMessage> history,
            String skillName
    ) {
        Objects.requireNonNull(sessionSummary, "sessionSummary must not be null.");
        Objects.requireNonNull(history, "history must not be null.");

        // 用户显式触发路径不经过模型，
        // 这里直接从当前历史恢复已激活集合并调用统一服务，
        // 让用户触发和模型工具触发共享完全一致的激活语义。
        SkillActivationResult result = skillActivationService.activate(
                ActivatedSkillSet.fromMessages(history),
                skillName
        );
        List<ConversationMessage> nextHistory = new ArrayList<>(history);
        nextHistory.addAll(result.appendedMessages());
        return new InteractiveTurnResult(nextHistory, result.output());
    }

    private ToolRegistry createToolRegistry() {
        ToolRegistry toolRegistry = new ToolRegistry();
        BuiltinToolRegistrar.registerAll(toolRegistry, workspaceRoot, skillLoader);
        return toolRegistry;
    }

    private DynamicPromptContext buildDynamicPromptContext(
            SessionSummary sessionSummary,
            List<ToolDefinition> availableTools
    ) {
        return new DynamicPromptContext(
                // 这里把 session 身份单独写进动态段，
                // 让多轮交互从第一轮开始就能看见自己归属哪一个控制面会话。
                "sessionId=%s, requestedBy=%s, status=%s".formatted(
                        sessionSummary.sessionId(),
                        sessionSummary.requestedBy(),
                        sessionSummary.status()
                ),
                "workspaceId=%s".formatted(sessionSummary.workspaceId()),
                skillLoader.loadIndex().summaries(),
                "maxSteps=%d".formatted(maxSteps),
                availableTools,
                Map.of(
                        "sessionId", sessionSummary.sessionId(),
                        "startedAt", Instant.now(clock).toString()
                )
        );
    }

    private List<ConversationMessage> rebuildPromptBoundary(
            SessionSummary sessionSummary,
            List<ConversationMessage> history,
            ToolRegistry toolRegistry
    ) {
        // 先把旧的基础 prompt 和运行时上下文剥掉，
        // 避免每轮重建时把旧边界一层层叠加进历史。
        List<ConversationMessage> preservedHistory = stripPromptBoundaryMessages(history);
        // 再根据“剥离后的真实会话历史”重算当前有效工具子集，
        // 这样中途激活 Skill 后，下一轮 prompt 能立即收缩工具说明。
        List<ToolDefinition> effectiveTools = resolveEffectiveTools(preservedHistory, toolRegistry);
        // 用最新 session 信息和最新工具子集重建 prompt 边界，
        // 保证模型每轮看到的 system prompt 都和当前约束一致。
        SystemPromptBoundary promptBoundary = systemPromptBuilder.build(
                buildDynamicPromptContext(sessionSummary, effectiveTools)
        );

        // 最后把新边界放回最前面，
        // 再拼接真正需要保留的历史消息，形成下一轮完整上下文。
        List<ConversationMessage> nextHistory = new ArrayList<>(buildInitialMessages(promptBoundary));
        nextHistory.addAll(preservedHistory);
        return List.copyOf(nextHistory);
    }

    private List<ToolDefinition> resolveEffectiveTools(
            List<ConversationMessage> history,
            ToolRegistry toolRegistry
    ) {
        // 先拿到 runtime 当前注册的全局工具定义，
        // 这是任何 Skill 约束计算的上界。
        List<ToolDefinition> globalTools = toolRegistry.list();
        // 再把消息历史里的已激活 Skill 约束折叠成最终允许工具名集合，
        // 规则固定为“全局工具 ∩ 所有受约束 Skill 的 allowed-tools 交集”。
        List<String> effectiveToolNames = ActivatedSkillSet.fromMessages(history)
                .resolveEffectiveAllowedTools(globalTools.stream().map(ToolDefinition::name).toList());

        // 最后回到完整 ToolDefinition 列表，
        // 只把仍然留在有效子集里的定义传给 prompt 和模型适配层。
        return globalTools.stream()
                .filter(toolDefinition -> effectiveToolNames.contains(toolDefinition.name()))
                .toList();
    }

    private List<ConversationMessage> stripPromptBoundaryMessages(List<ConversationMessage> history) {
        if (history.isEmpty()) {
            return List.of();
        }

        // 这里只剥离由当前 runner 自动生成的两条 prompt 边界消息，
        // 用户历史、模型输出、工具结果、Activated Skill 记录都必须完整保留。
        int index = 0;
        if (index < history.size() && isBasePromptMessage(history.get(index))) {
            index += 1;
        }
        if (index < history.size() && isRuntimeContextMessage(history.get(index))) {
            index += 1;
        }
        return List.copyOf(history.subList(index, history.size()));
    }

    private boolean isBasePromptMessage(ConversationMessage message) {
        return message.role() == MessageRole.SYSTEM && message.content().contains("# 基础指令");
    }

    private boolean isRuntimeContextMessage(ConversationMessage message) {
        return message.role() == MessageRole.SYSTEM && message.content().contains("# 运行时上下文");
    }

    private List<ConversationMessage> buildInitialMessages(SystemPromptBoundary promptBoundary) {
        List<ConversationMessage> messages = new ArrayList<>();

        // 第一条 SYSTEM 固定承载稳定基础指令和会话指令，
        // 这样后续每一轮都能复用同一个 prompt 前缀。
        messages.add(new ConversationMessage(MessageRole.SYSTEM, promptBoundary.systemPrompt()));

        // 第二条 SYSTEM 独立承载运行时上下文块，
        // 保持与稳定前缀分离，避免频繁变化的信息污染静态 prompt。
        if (promptBoundary.hasRuntimeContextBlock()) {
            messages.add(new ConversationMessage(MessageRole.SYSTEM, promptBoundary.runtimeContextBlock()));
        }

        return List.copyOf(messages);
    }

    private String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    private static Path resolveUserHome() {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            throw new IllegalStateException("user.home 系统属性缺失。");
        }
        return Path.of(userHome).toAbsolutePath().normalize();
    }
}
