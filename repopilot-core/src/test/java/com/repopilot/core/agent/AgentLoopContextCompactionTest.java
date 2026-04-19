package com.repopilot.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.context.ContextCompactionPolicy;
import com.repopilot.core.context.ContextCompactor;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import com.repopilot.core.trace.TracePublisher;
import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolRegistry;
import com.repopilot.protocol.trace.TraceEventType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentLoopContextCompactionTest {

    @Test
    void shouldInjectWorkingMemoryAndContextSummaryBeforeSecondModelCall() {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register("echo", "回显输入文本", arguments -> ToolExecutionResult.success(arguments.get("text")));

        RecordingTracePublisher tracePublisher = new RecordingTracePublisher();
        RecordingModelAdapter modelAdapter = new RecordingModelAdapter(List.of(
                new ToolCallModelResponse(List.of(new ToolCall("call-1", "echo", Map.of("text", "pom.xml")))),
                new FinalModelResponse("分析完成")
        ));

        AgentLoopResult result = new AgentLoop(
                toolRegistry,
                AgentLoopObserver.noop(),
                tracePublisher,
                new ContextCompactor(new ContextCompactionPolicy(2, 2, 2))
        ).run(new AgentLoopRequest(
                modelAdapter,
                List.of(new ConversationMessage(MessageRole.USER, "读取 pom.xml")),
                4
        ));

        List<ConversationMessage> secondCallMessages = modelAdapter.calls().get(1);

        assertEquals("分析完成", result.finalAnswer());
        assertTrue(secondCallMessages.stream().anyMatch(message -> message.role() == MessageRole.WORKING_MEMORY));
        assertTrue(secondCallMessages.stream().anyMatch(message -> message.role() == MessageRole.CONTEXT_SUMMARY));
        assertFalse(secondCallMessages.stream().anyMatch(message -> message.role() == MessageRole.USER));
        assertTrue(secondCallMessages.stream()
                .filter(message -> message.role() == MessageRole.WORKING_MEMORY)
                .findFirst()
                .orElseThrow()
                .content()
                .contains("task_goal: 读取 pom.xml"));

        assertEquals(
                List.of(
                        TraceEventType.MODEL_CALL_REQUESTED,
                        TraceEventType.MODEL_RESPONSE_RECEIVED,
                        TraceEventType.TOOL_CALL_REQUESTED,
                        TraceEventType.TOOL_CALL_COMPLETED,
                        TraceEventType.CONTEXT_COMPACTION_STARTED,
                        TraceEventType.CONTEXT_COMPACTION_COMPLETED,
                        TraceEventType.MODEL_CALL_REQUESTED,
                        TraceEventType.MODEL_RESPONSE_RECEIVED
                ),
                tracePublisher.events().stream().map(TracePublisher.TraceEvent::type).toList()
        );
        assertEquals("1", tracePublisher.events().get(5).metadata().get("compactedMessageCount"));
        assertEquals("compaction-1", tracePublisher.events().get(5).metadata().get("checkpointId"));
    }

    private static final class RecordingTracePublisher implements TracePublisher {

        private final List<TraceEvent> events = new ArrayList<>();

        @Override
        public void publish(TraceEvent event) {
            events.add(event);
        }

        private List<TraceEvent> events() {
            return events;
        }
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
            calls.add(messages);
            ModelResponse response = scriptedResponses.get(cursor);
            cursor += 1;
            return response;
        }

        private List<List<ConversationMessage>> calls() {
            return calls;
        }
    }
}
