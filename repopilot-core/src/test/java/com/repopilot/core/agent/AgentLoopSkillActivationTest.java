package com.repopilot.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import com.repopilot.core.tool.ToolExecutionContext;
import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolHandler;
import com.repopilot.core.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentLoopSkillActivationTest {

    @Test
    void shouldAppendAdditionalSystemMessagesReturnedByToolExecution() {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register("activate_skill", "激活 Skill", new ToolHandler() {
            @Override
            public ToolExecutionResult execute(Map<String, String> arguments) {
                throw new IllegalStateException("测试应该走带上下文的执行重载。");
            }

            @Override
            public ToolExecutionResult execute(ToolExecutionContext context, Map<String, String> arguments) {
                ConversationMessage activatedSkillMessage = new ConversationMessage(
                        MessageRole.SYSTEM,
                        "# Activated Skill\nname: debug\nsource: project\n\n## Debug Skill\n先复现，再缩小范围。"
                );

                return ToolExecutionResult.success(
                        "Skill debug 已激活。",
                        List.of(activatedSkillMessage)
                );
            }
        });

        RecordingModelAdapter modelAdapter = new RecordingModelAdapter(List.of(
                new ToolCallModelResponse(List.of(new ToolCall("call-1", "activate_skill", Map.of("name", "debug")))),
                new FinalModelResponse("继续处理任务")
        ));

        AgentLoopResult result = new AgentLoop(toolRegistry).run(new AgentLoopRequest(
                modelAdapter,
                List.of(new ConversationMessage(MessageRole.USER, "激活 debug 并继续")),
                4
        ));

        List<ConversationMessage> secondCallMessages = modelAdapter.calls().get(1);

        assertEquals("继续处理任务", result.finalAnswer());
        assertTrue(secondCallMessages.stream()
                .filter(message -> message.role() == MessageRole.SYSTEM)
                .anyMatch(message -> message.content().contains("# Activated Skill")));
    }

    private static final class RecordingModelAdapter implements ModelAdapter {

        private final List<ModelResponse> scriptedResponses;
        private final List<List<ConversationMessage>> calls = new ArrayList<>();
        private int cursor;

        private RecordingModelAdapter(List<ModelResponse> scriptedResponses) {
            this.scriptedResponses = scriptedResponses;
        }

        @Override
        public ModelResponse next(List<ConversationMessage> messages) {
            calls.add(List.copyOf(messages));
            ModelResponse response = scriptedResponses.get(cursor);
            cursor += 1;
            return response;
        }

        private List<List<ConversationMessage>> calls() {
            return calls;
        }
    }
}
