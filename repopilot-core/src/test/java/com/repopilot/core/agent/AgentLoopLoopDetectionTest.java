package com.repopilot.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.repopilot.core.agent.loop.ToolCallLoopDetectedException;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentLoopLoopDetectionTest {

    @Test
    void shouldInterruptTurnBeforeExecutingToolCallThatReachesRepeatThreshold() {
        AtomicInteger executionCount = new AtomicInteger();
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register("read_file", "读取文件", arguments -> {
            executionCount.incrementAndGet();
            return ToolExecutionResult.success("文件内容");
        });

        ModelAdapter modelAdapter = new ScriptedModelAdapter(List.of(
                new ToolCallModelResponse(List.of(new ToolCall("call-1", "read_file", Map.of("path", "README.md")))),
                new ToolCallModelResponse(List.of(new ToolCall("call-2", "read_file", Map.of("path", "README.md")))),
                new ToolCallModelResponse(List.of(new ToolCall("call-3", "read_file", Map.of("path", "README.md"))))
        ));

        ToolCallLoopDetectedException exception = assertThrows(
                ToolCallLoopDetectedException.class,
                () -> new AgentLoop(toolRegistry).run(new AgentLoopRequest(
                        modelAdapter,
                        List.of(new ConversationMessage(MessageRole.USER, "读取 README.md")),
                        5,
                        3
                ))
        );

        assertEquals(2, executionCount.get());
        assertEquals(
                "连续重复工具调用导致终止: toolName=read_file, repeatCount=3, arguments=path=README.md",
                exception.getMessage()
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
