package com.repopilot.core.agent;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * RepoPilot 的最小 ReAct 主循环。
 * 当前版本只做一件事：在“模型输出工具调用”和“模型输出最终回答”之间循环切换。
 */
public class AgentLoop {

    private final ToolRegistry toolRegistry;
    private final AgentLoopObserver observer;

    public AgentLoop(ToolRegistry toolRegistry) {
        this(toolRegistry, AgentLoopObserver.noop());
    }

    public AgentLoop(ToolRegistry toolRegistry, AgentLoopObserver observer) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null.");
        this.observer = Objects.requireNonNull(observer, "observer must not be null.");
    }

    public AgentLoopResult run(AgentLoopRequest request) {
        List<ConversationMessage> messages = new ArrayList<>(request.messages());

        for (int step = 0; step < request.maxSteps(); step++) {
            // 每一轮 step 开始前先把当前消息快照暴露给观察器，
            // 这样 CLI 或 trace 层就能看到“模型即将基于什么上下文继续推理”。
            observer.onStepStarted(step + 1, List.copyOf(messages));
            ModelResponse response = request.modelAdapter().next(List.copyOf(messages));
            observer.onModelResponse(step + 1, response);

            if (response instanceof FinalModelResponse finalResponse) {
                ConversationMessage assistantMessage =
                        new ConversationMessage(MessageRole.ASSISTANT, finalResponse.message());
                messages.add(assistantMessage);
                return new AgentLoopResult(messages, finalResponse.message());
            }

            if (response instanceof ToolCallModelResponse toolCallResponse) {
                // 先把 assistant 的 tool_calls 记录进消息历史，
                // 这样下一轮真实模型调用时，协议里就能形成
                // `assistant(tool_calls) -> tool(tool_call_id)` 的正确配对关系。
                messages.add(ConversationMessage.assistantToolCalls(toolCallResponse.toolCalls()));

                for (ToolCall toolCall : toolCallResponse.toolCalls()) {
                    // 先通知工具即将开始，
                    // 让外层观察器能够打印工具名和参数摘要。
                    observer.onToolExecutionStarted(step + 1, toolCall);
                    ToolExecutionResult executionResult =
                            toolRegistry.execute(toolCall.toolName(), toolCall.arguments());
                    // 工具返回后立刻通知观察器，
                    // 即使结果是致命错误，也保证外层能先拿到真实失败信息。
                    observer.onToolExecutionFinished(step + 1, toolCall, executionResult);

                    // 致命错误说明主链路已经失真，必须立刻暴露真实错误，
                    // 不能继续伪装成一条普通 TOOL 消息喂给下一轮模型。
                    failIfFatal(toolCall.toolName(), executionResult);

                    // 工具结果会被重新注入消息列表，
                    // 让下一轮模型推理能够“看到自己刚刚调用工具后发生了什么”。
                    ConversationMessage toolMessage = ConversationMessage.toolResult(
                            toolCall.id(),
                            formatToolMessage(toolCall.toolName(), executionResult)
                    );
                    messages.add(toolMessage);

                    // TOOL 消息只有在真正成功追加进历史后才通知观察器，
                    // 这样外层调试输出看到的就是下一轮模型会实际收到的消息内容。
                    observer.onToolMessageAdded(step + 1, toolCall, toolMessage);
                }
            }
        }

        throw new AgentLoopLimitExceededException(request.maxSteps());
    }

    private void failIfFatal(String toolName, ToolExecutionResult executionResult) {
        if (executionResult.isFatal()) {
            throw new IllegalStateException(
                    "Tool execution failed fatally: " + toolName + ", output=" + executionResult.output()
            );
        }
    }

    private String formatToolMessage(String toolName, ToolExecutionResult executionResult) {
        return switch (executionResult.status()) {
            case SUCCESS -> "[" + toolName + "] " + executionResult.output();
            case RECOVERABLE_ERROR -> "[" + toolName + ":error] " + executionResult.output();
            case FATAL_ERROR -> throw new IllegalArgumentException(
                    "Fatal tool execution must not be formatted as a TOOL message."
            );
        };
    }
}
