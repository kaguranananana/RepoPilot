package com.repopilot.cli.interactive;

import com.repopilot.cli.runtime.CliRuntimeBootstrap;
import com.repopilot.core.agent.AgentLoop;
import com.repopilot.core.agent.AgentLoopObserver;
import com.repopilot.core.agent.AgentLoopRequest;
import com.repopilot.core.agent.AgentLoopResult;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.prompt.DynamicPromptContext;
import com.repopilot.core.prompt.SystemPromptBoundary;
import com.repopilot.core.prompt.SystemPromptBuilder;
import com.repopilot.core.tool.ToolRegistry;
import com.repopilot.core.tool.builtin.BuiltinToolRegistrar;
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
                maxSteps
        );
    }

    DefaultInteractiveRuntimeRunner(
            Clock clock,
            SystemPromptBuilder systemPromptBuilder,
            CliRuntimeBootstrap.ModelAdapterFactory modelAdapterFactory,
            Path workspaceRoot,
            int maxSteps
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
    }

    @Override
    public List<ConversationMessage> createInitialHistory(SessionSummary sessionSummary) {
        Objects.requireNonNull(sessionSummary, "sessionSummary must not be null.");

        ToolRegistry toolRegistry = createToolRegistry();
        SystemPromptBoundary promptBoundary = systemPromptBuilder.build(buildDynamicPromptContext(sessionSummary, toolRegistry));
        return buildInitialMessages(promptBoundary);
    }

    @Override
    public InteractiveTurnResult runTurn(
            SessionSummary sessionSummary,
            List<ConversationMessage> history,
            String prompt,
            AgentLoopObserver observer
    ) {
        Objects.requireNonNull(sessionSummary, "sessionSummary must not be null.");
        Objects.requireNonNull(history, "history must not be null.");
        Objects.requireNonNull(observer, "observer must not be null.");
        String safePrompt = requireNonBlank(prompt, "prompt must not be blank.");

        ToolRegistry toolRegistry = createToolRegistry();
        List<ConversationMessage> messages = new ArrayList<>(history);

        // 每次新输入都只追加一条新的 USER 消息，
        // 前面的 SYSTEM / USER / ASSISTANT / TOOL 历史保持原样继续复用。
        messages.add(new ConversationMessage(MessageRole.USER, safePrompt));

        AgentLoop agentLoop = new AgentLoop(toolRegistry, observer);
        AgentLoopResult result = agentLoop.run(new AgentLoopRequest(
                modelAdapterFactory.create(sessionSummary, toolRegistry.list()),
                messages,
                maxSteps
        ));

        return new InteractiveTurnResult(result.messages(), result.finalAnswer());
    }

    private ToolRegistry createToolRegistry() {
        ToolRegistry toolRegistry = new ToolRegistry();
        BuiltinToolRegistrar.registerAll(toolRegistry, workspaceRoot);
        return toolRegistry;
    }

    private DynamicPromptContext buildDynamicPromptContext(
            SessionSummary sessionSummary,
            ToolRegistry toolRegistry
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
                List.of(),
                "maxSteps=%d".formatted(maxSteps),
                toolRegistry.list(),
                Map.of(
                        "sessionId", sessionSummary.sessionId(),
                        "startedAt", Instant.now(clock).toString()
                )
        );
    }

    private List<ConversationMessage> buildInitialMessages(SystemPromptBoundary promptBoundary) {
        List<ConversationMessage> messages = new ArrayList<>();

        // 第一条 SYSTEM 固定承载稳定宪法和动态政策，
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
}
