package com.repopilot.cli.runtime;

import com.repopilot.core.agent.AgentLoop;
import com.repopilot.core.agent.AgentLoopObserver;
import com.repopilot.core.agent.AgentLoopRequest;
import com.repopilot.core.agent.AgentLoopResult;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.prompt.DynamicPromptContext;
import com.repopilot.core.prompt.SystemPromptBoundary;
import com.repopilot.core.prompt.SystemPromptBuilder;
import com.repopilot.core.permission.WorkspacePermissionPolicy;
import com.repopilot.core.review.DiffReviewService;
import com.repopilot.core.skill.SkillLoader;
import com.repopilot.core.trace.TracePublisher;
import com.repopilot.core.tool.ToolDefinition;
import com.repopilot.core.tool.builtin.BuiltinToolRegistrar;
import com.repopilot.core.tool.ToolRegistry;
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
 * CLI 侧 runtime 引导器。
 * 它负责把控制面的 session 信息和用户 prompt 组装成一次最小 core 运行，
 * 从而让 CLI 能在没有远程执行器的前提下先跑通本地主链路。
 */
@FunctionalInterface
public interface CliRuntimeBootstrap {

    int DEFAULT_MAX_STEPS = 12;

    String run(SessionSummary sessionSummary, String prompt, TracePublisher tracePublisher, int maxSteps);

    default String run(SessionSummary sessionSummary, String prompt, TracePublisher tracePublisher) {
        return run(sessionSummary, prompt, tracePublisher, DEFAULT_MAX_STEPS);
    }

    default String run(SessionSummary sessionSummary, String prompt) {
        return run(sessionSummary, prompt, TracePublisher.noop());
    }

    static CliRuntimeBootstrap createDefault() {
        // 默认入口固定从当前工作区根目录读取 `.env.local`，
        // 再让真实进程环境覆盖同名变量，保证显式 export 的值优先级更高。
        Path workspaceRoot = Path.of("").toAbsolutePath().normalize();
        return new DefaultCliRuntimeBootstrap(
                Clock.systemUTC(),
                new SystemPromptBuilder(),
                SkillLoader.createDefault(workspaceRoot, resolveUserHome()),
                new EnvironmentBackedModelAdapterFactory(
                        LocalEnvironmentMapLoader.load(workspaceRoot, System.getenv())
                )
        );
    }

    /**
     * 默认 bootstrap 使用当前最小 runtime 组件完成一次本地运行。
     * 当 provider=bootstrap 时，它会走确定性假模型；
     * 当 provider=openai-compatible 时，它会走真实模型调用。
     */
    final class DefaultCliRuntimeBootstrap implements CliRuntimeBootstrap {

        private final Clock clock;
        private final SystemPromptBuilder systemPromptBuilder;
        private final SkillLoader skillLoader;
        private final ModelAdapterFactory modelAdapterFactory;

        DefaultCliRuntimeBootstrap(
                Clock clock,
                SystemPromptBuilder systemPromptBuilder,
                ModelAdapterFactory modelAdapterFactory
        ) {
            this(
                    clock,
                    systemPromptBuilder,
                    SkillLoader.createDefault(Path.of("").toAbsolutePath().normalize(), resolveUserHome()),
                    modelAdapterFactory
            );
        }

        DefaultCliRuntimeBootstrap(
                Clock clock,
                SystemPromptBuilder systemPromptBuilder,
                SkillLoader skillLoader,
                ModelAdapterFactory modelAdapterFactory
        ) {
            this.clock = Objects.requireNonNull(clock, "clock must not be null.");
            this.systemPromptBuilder =
                    Objects.requireNonNull(systemPromptBuilder, "systemPromptBuilder must not be null.");
            this.skillLoader = Objects.requireNonNull(skillLoader, "skillLoader must not be null.");
            this.modelAdapterFactory =
                    Objects.requireNonNull(modelAdapterFactory, "modelAdapterFactory must not be null.");
        }

        @Override
        public String run(
                SessionSummary sessionSummary,
                String prompt,
                TracePublisher tracePublisher,
                int maxSteps
        ) {
            Objects.requireNonNull(sessionSummary, "sessionSummary must not be null.");
            Objects.requireNonNull(tracePublisher, "tracePublisher must not be null.");
            String safePrompt = requireNonBlank(prompt, "prompt must not be blank.");
            if (maxSteps <= 0) {
                throw new IllegalArgumentException("maxSteps must be greater than zero.");
            }
            Path workspaceRoot = Path.of("").toAbsolutePath().normalize();

            // 先建立本轮可见的最小工具集合。
            // 这里显式把第一批内置工具注册进来，
            // 让 dynamic prompt 和后续运行时执行链路看到的是同一套真实能力。
            ToolRegistry toolRegistry = new ToolRegistry();
            BuiltinToolRegistrar.registerAll(toolRegistry, workspaceRoot, skillLoader);
            GovernedToolExecutor governedToolExecutor = new GovernedToolExecutor(
                    toolRegistry,
                    new WorkspacePermissionPolicy(workspaceRoot),
                    new DiffReviewService(workspaceRoot)
            );

            // 再把稳定基础指令、会话指令和运行时 metadata 分块组装，
            // 避免把 sessionId、时间等高频信息直接混进稳定 system prompt 前缀。
            SystemPromptBoundary promptBoundary = systemPromptBuilder.build(
                    buildDynamicPromptContext(sessionSummary, toolRegistry, maxSteps)
            );

            // 最后把拼好的消息列表送进 AgentLoop，
            // 让 CLI 到 core 的一次最小调用真正走过统一运行时入口。
            AgentLoop agentLoop = new AgentLoop(
                    governedToolExecutor,
                    AgentLoopObserver.noop(),
                    tracePublisher,
                    CliContextCompactionFactory.createContextCompactor(),
                    CliContextCompactionFactory.createInputTokenEstimator(toolRegistry.list())
            );
            AgentLoopResult result = agentLoop.run(new AgentLoopRequest(
                    modelAdapterFactory.create(sessionSummary, toolRegistry.list()),
                    buildMessages(promptBoundary, safePrompt),
                    maxSteps
            ));

            return result.finalAnswer();
        }

        private DynamicPromptContext buildDynamicPromptContext(
                SessionSummary sessionSummary,
                ToolRegistry toolRegistry,
                int maxSteps
        ) {
            return new DynamicPromptContext(
                    // 这一段描述当前会话身份，
                    // 让模型在最小闭环里也能看到“我是谁、这是谁发起的 session”。
                    "sessionId=%s, requestedBy=%s, status=%s".formatted(
                            sessionSummary.sessionId(),
                            sessionSummary.requestedBy(),
                            sessionSummary.status()
                    ),
                    // 工作区信息单独成段，
                    // 保持与会话前导、预算提示等动态信息分离。
                    "workspaceId=%s".formatted(sessionSummary.workspaceId()),
                    skillLoader.loadIndex().summaries(),
                    // maxSteps 来自 CLI 显式预算，
                    // 让真实模型 E2E 可以按任务复杂度提高上限，同时仍保留硬停止边界。
                    "maxSteps=%d".formatted(maxSteps),
                    toolRegistry.list(),
                    Map.of(
                            "sessionId", sessionSummary.sessionId(),
                            "startedAt", Instant.now(clock).toString()
                    )
            );
        }

        private List<ConversationMessage> buildMessages(SystemPromptBoundary promptBoundary, String prompt) {
            List<ConversationMessage> messages = new ArrayList<>();

            // 第一条 SYSTEM 消息放稳定 system prompt，
            // 这里包含基础指令和会话指令，但不包含高频 runtime metadata。
            messages.add(new ConversationMessage(MessageRole.SYSTEM, promptBoundary.systemPrompt()));

            // 第二条 SYSTEM 消息单独承载 runtime context block，
            // 这样高频运行时数据不会污染稳定前缀，同时又能在本轮推理中被看到。
            if (promptBoundary.hasRuntimeContextBlock()) {
                messages.add(new ConversationMessage(MessageRole.SYSTEM, promptBoundary.runtimeContextBlock()));
            }

            // 最后一条 USER 消息才是真正的用户任务，
            // 保证 prompt 边界清晰，便于后续扩展上下文压缩与 trace。
            messages.add(new ConversationMessage(MessageRole.USER, prompt));
            return List.copyOf(messages);
        }

        private String requireNonBlank(String value, String message) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(message);
            }
            return value.strip();
        }
    }

    private static Path resolveUserHome() {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            throw new IllegalStateException("user.home 系统属性缺失。");
        }
        return Path.of(userHome).toAbsolutePath().normalize();
    }

    @FunctionalInterface
    interface ModelAdapterFactory {

        ModelAdapter create(SessionSummary sessionSummary, List<ToolDefinition> availableTools);
    }

    final class EnvironmentBackedModelAdapterFactory implements ModelAdapterFactory {

        private final CliModelConfig modelConfig;

        public EnvironmentBackedModelAdapterFactory(Map<String, String> environment) {
            this.modelConfig = CliModelConfig.fromEnvironment(environment);
        }

        @Override
        public ModelAdapter create(SessionSummary sessionSummary, List<ToolDefinition> availableTools) {
            Objects.requireNonNull(sessionSummary, "sessionSummary must not be null.");
            Objects.requireNonNull(availableTools, "availableTools must not be null.");

            return switch (modelConfig.provider()) {
                case "bootstrap" -> new BootstrapModelAdapter(sessionSummary);
                case "openai-compatible" -> new OpenAiCompatibleChatModelAdapter(
                        modelConfig.apiKey(),
                        modelConfig.baseUrl(),
                        modelConfig.modelName(),
                        availableTools
                );
                default -> throw new IllegalStateException("Unsupported model provider: " + modelConfig.provider());
            };
        }
    }

    /**
     * 当前阶段的最小模型适配器。
     * 它不负责真实推理，只负责证明 prompt 已经成功进入 core 主循环。
     */
    final class BootstrapModelAdapter implements ModelAdapter {

        private final SessionSummary sessionSummary;

        BootstrapModelAdapter(SessionSummary sessionSummary) {
            this.sessionSummary = Objects.requireNonNull(sessionSummary, "sessionSummary must not be null.");
        }

        @Override
        public ModelResponse next(List<ConversationMessage> messages) {
            // 从消息尾部反向查找最后一条 USER 消息，
            // 保证我们回显的是“真正送进 runtime 的本轮任务”。
            String latestUserPrompt = extractLatestUserPrompt(messages);

            // 当前没有接真实模型，因此直接返回确定性最终回答。
            // 这样测试可以稳定验证 CLI 已经把 prompt 成功送达 core。
            return new FinalModelResponse(
                    "RepoPilot runtime accepted prompt for session %s: %s".formatted(
                            sessionSummary.sessionId(),
                            latestUserPrompt
                    )
            );
        }

        private String extractLatestUserPrompt(List<ConversationMessage> messages) {
            for (int index = messages.size() - 1; index >= 0; index--) {
                ConversationMessage message = messages.get(index);
                if (message.role() == MessageRole.USER) {
                    return message.content();
                }
            }
            throw new IllegalArgumentException("messages must contain at least one USER message.");
        }
    }
}
