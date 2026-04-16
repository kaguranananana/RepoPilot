package com.repopilot.cli.runtime;

import com.repopilot.core.agent.AgentLoop;
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
import com.repopilot.core.tool.ToolRegistry;
import com.repopilot.protocol.session.SessionSummary;
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

    String run(SessionSummary sessionSummary, String prompt);

    static CliRuntimeBootstrap createDefault() {
        return new DefaultCliRuntimeBootstrap(Clock.systemUTC(), new SystemPromptBuilder());
    }

    /**
     * 默认 bootstrap 使用当前最小 runtime 组件完成一次单回合执行。
     * 当前阶段还没有接真实模型，所以这里故意使用确定性适配器，
     * 先验证“session -> prompt 组装 -> core 运行 -> 最终回答”这条链路本身。
     */
    final class DefaultCliRuntimeBootstrap implements CliRuntimeBootstrap {

        private final Clock clock;
        private final SystemPromptBuilder systemPromptBuilder;

        DefaultCliRuntimeBootstrap(Clock clock, SystemPromptBuilder systemPromptBuilder) {
            this.clock = Objects.requireNonNull(clock, "clock must not be null.");
            this.systemPromptBuilder =
                    Objects.requireNonNull(systemPromptBuilder, "systemPromptBuilder must not be null.");
        }

        @Override
        public String run(SessionSummary sessionSummary, String prompt) {
            Objects.requireNonNull(sessionSummary, "sessionSummary must not be null.");
            String safePrompt = requireNonBlank(prompt, "prompt must not be blank.");

            // 先建立本轮可见的最小工具集合。
            // 当前阶段还没有挂真实工具，所以这里先传入一个空 registry，
            // 但主循环的装配方式已经和后续真实运行时保持一致。
            ToolRegistry toolRegistry = new ToolRegistry();

            // 再把稳定宪法、动态政策和运行时 metadata 分块组装，
            // 避免把 sessionId、时间等高频信息直接混进稳定 system prompt 前缀。
            SystemPromptBoundary promptBoundary = systemPromptBuilder.build(
                    buildDynamicPromptContext(sessionSummary, toolRegistry)
            );

            // 最后把拼好的消息列表送进 AgentLoop，
            // 让 CLI 到 core 的一次最小调用真正走过统一运行时入口。
            AgentLoop agentLoop = new AgentLoop(toolRegistry);
            AgentLoopResult result = agentLoop.run(new AgentLoopRequest(
                    new BootstrapModelAdapter(sessionSummary),
                    buildMessages(promptBoundary, safePrompt),
                    1
            ));

            return result.finalAnswer();
        }

        private DynamicPromptContext buildDynamicPromptContext(
                SessionSummary sessionSummary,
                ToolRegistry toolRegistry
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
                    List.of(),
                    // 当前适配器只返回最终回答，不走工具循环，
                    // 因此这里把预算明确固定为 1 步。
                    "maxSteps=1",
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
            // 这里包含静态宪法和动态政策，但不包含高频 runtime metadata。
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
