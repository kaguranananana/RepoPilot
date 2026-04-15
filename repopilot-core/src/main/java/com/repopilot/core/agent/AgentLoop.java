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

    public AgentLoop(ToolRegistry toolRegistry) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null.");
    }

    public AgentLoopResult run(AgentLoopRequest request) {
        List<ConversationMessage> messages = new ArrayList<>(request.messages());

        for (int step = 0; step < request.maxSteps(); step++) {
            ModelResponse response = request.modelAdapter().next(List.copyOf(messages));

            if (response instanceof FinalModelResponse finalResponse) {
                ConversationMessage assistantMessage =
                        new ConversationMessage(MessageRole.ASSISTANT, finalResponse.message());
                messages.add(assistantMessage);
                return new AgentLoopResult(messages, finalResponse.message());
            }

            if (response instanceof ToolCallModelResponse toolCallResponse) {
                for (ToolCall toolCall : toolCallResponse.toolCalls()) {
                    ToolExecutionResult executionResult =
                            toolRegistry.execute(toolCall.toolName(), toolCall.arguments());

                    // 工具结果会被重新注入消息列表，
                    // 让下一轮模型推理能够“看到自己刚刚调用工具后发生了什么”。
                    messages.add(new ConversationMessage(
                            MessageRole.TOOL,
                            formatToolMessage(toolCall.toolName(), executionResult)
                    ));
                }
            }
        }

        throw new AgentLoopLimitExceededException(request.maxSteps());
    }

    private String formatToolMessage(String toolName, ToolExecutionResult executionResult) {
        if (executionResult.success()) {
            return "[" + toolName + "] " + executionResult.output();
        }
        return "[" + toolName + ":error] " + executionResult.output();
    }
}
