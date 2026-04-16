package com.repopilot.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentLoopTest {

    @Test
    void shouldFinishTurnAfterToolCallAndFinalAnswer() {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register("echo", "回显输入文本", arguments -> ToolExecutionResult.success(arguments.get("text")));

        ModelAdapter modelAdapter = new ScriptedModelAdapter(List.of(
                new ToolCallModelResponse(List.of(new ToolCall("call-1", "echo", Map.of("text", "pom.xml")))),
                new FinalModelResponse("分析完成")
        ));

        AgentLoopResult result = new AgentLoop(toolRegistry).run(
                new AgentLoopRequest(
                        modelAdapter,
                        List.of(new ConversationMessage(MessageRole.USER, "读取 pom.xml")),
                        4
                )
        );

        assertEquals("分析完成", result.finalAnswer());
        assertEquals(3, result.messages().size());
        assertEquals(MessageRole.TOOL, result.messages().get(1).role());
        assertEquals("[echo] pom.xml", result.messages().get(1).content());
        assertEquals(MessageRole.ASSISTANT, result.messages().get(2).role());
    }

    @Test
    void shouldContinueTurnAfterRecoverableToolError() {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(
                "read_file",
                "读取文件",
                arguments -> ToolExecutionResult.recoverableError("文件不存在: " + arguments.get("path"))
        );

        ModelAdapter modelAdapter = new ScriptedModelAdapter(List.of(
                new ToolCallModelResponse(List.of(new ToolCall("call-1", "read_file", Map.of("path", "missing.txt")))),
                new FinalModelResponse("请检查文件路径后重试")
        ));

        AgentLoopResult result = new AgentLoop(toolRegistry).run(
                new AgentLoopRequest(
                        modelAdapter,
                        List.of(new ConversationMessage(MessageRole.USER, "读取 missing.txt")),
                        4
                )
        );

        assertEquals("请检查文件路径后重试", result.finalAnswer());
        assertEquals(MessageRole.TOOL, result.messages().get(1).role());
        assertEquals("[read_file:error] 文件不存在: missing.txt", result.messages().get(1).content());
    }

    @Test
    void shouldFailFastWhenToolReturnsFatalError() {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(
                "write_file",
                "写入文件",
                arguments -> ToolExecutionResult.fatalError("权限拒绝: " + arguments.get("path"))
        );

        ModelAdapter modelAdapter = new ScriptedModelAdapter(List.of(
                new ToolCallModelResponse(List.of(new ToolCall("call-1", "write_file", Map.of("path", "/root/secret.txt"))))
        ));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new AgentLoop(toolRegistry).run(new AgentLoopRequest(
                        modelAdapter,
                        List.of(new ConversationMessage(MessageRole.USER, "写入 /root/secret.txt")),
                        4
                ))
        );

        assertEquals("Tool execution failed fatally: write_file, output=权限拒绝: /root/secret.txt", exception.getMessage());
    }

    @Test
    void shouldFailWhenModelExceedsMaxSteps() {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register("echo", "回显输入文本", arguments -> ToolExecutionResult.success(arguments.get("text")));

        ModelAdapter modelAdapter = new ScriptedModelAdapter(List.of(
                new ToolCallModelResponse(List.of(new ToolCall("call-1", "echo", Map.of("text", "first")))),
                new ToolCallModelResponse(List.of(new ToolCall("call-2", "echo", Map.of("text", "second"))))
        ));

        AgentLoop agentLoop = new AgentLoop(toolRegistry);

        assertThrows(
                AgentLoopLimitExceededException.class,
                () -> agentLoop.run(new AgentLoopRequest(
                        modelAdapter,
                        List.of(new ConversationMessage(MessageRole.USER, "重复调用工具")),
                        1
                ))
        );
    }

    private static final class ScriptedModelAdapter implements ModelAdapter {

        private final List<ModelResponse> scriptedResponses;
        private int cursor;

        private ScriptedModelAdapter(List<ModelResponse> scriptedResponses) {
            this.scriptedResponses = scriptedResponses;
        }

        @Override
        public ModelResponse next(List<ConversationMessage> messages) {
            ModelResponse response = scriptedResponses.get(cursor);
            cursor += 1;
            return response;
        }
    }
}
