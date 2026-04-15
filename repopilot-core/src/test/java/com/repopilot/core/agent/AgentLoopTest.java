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
        toolRegistry.register("echo", "回显输入文本", arguments -> new ToolExecutionResult(true, arguments.get("text")));

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
    void shouldFailWhenModelExceedsMaxSteps() {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register("echo", "回显输入文本", arguments -> new ToolExecutionResult(true, arguments.get("text")));

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

