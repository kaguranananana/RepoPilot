package com.repopilot.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.context.ContextCompactionPolicy;
import com.repopilot.core.context.ContextCompactor;
import com.repopilot.core.context.StructuredContextSummary;
import com.repopilot.core.context.StructuredContextSummaryGenerator;
import com.repopilot.core.context.StructuredContextSummaryRequest;
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

    @Test
    void shouldCompactWhenEstimatedInputTokensExceedBudget() {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register("echo", "回显输入文本", arguments -> ToolExecutionResult.success(arguments.get("text")));

        RecordingTracePublisher tracePublisher = new RecordingTracePublisher();
        RecordingModelAdapter modelAdapter = new RecordingModelAdapter(List.of(
                new ToolCallModelResponse(List.of(new ToolCall("call-1", "echo", Map.of("text", "large-output")))),
                new FinalModelResponse("分析完成")
        ));

        AgentLoopResult result = new AgentLoop(
                toolRegistry,
                AgentLoopObserver.noop(),
                tracePublisher,
                new ContextCompactor(new ContextCompactionPolicy(100, 2, 2, 50, 10, 10)),
                messages -> messages.stream().anyMatch(message -> message.role() == MessageRole.WORKING_MEMORY)
                        ? 10
                        : messages.size() >= 3 ? 31 : 10
        ).run(new AgentLoopRequest(
                modelAdapter,
                List.of(new ConversationMessage(MessageRole.USER, "读取大文件")),
                4
        ));

        assertEquals("分析完成", result.finalAnswer());
        assertTrue(modelAdapter.calls().get(1).stream().anyMatch(message -> message.role() == MessageRole.WORKING_MEMORY));
        TracePublisher.TraceEvent completed = tracePublisher.events().stream()
                .filter(event -> event.type() == TraceEventType.CONTEXT_COMPACTION_COMPLETED)
                .findFirst()
                .orElseThrow();
        assertEquals("TOKEN_BUDGET", completed.metadata().get("trigger"));
        assertEquals("31", completed.metadata().get("estimatedInputTokens"));
        assertEquals("30", completed.metadata().get("maxInputTokens"));
    }

    @Test
    void shouldUseStructuredModelSummaryWhenRuleCompactionStillExceedsTokenBudget() {
        RecordingStructuredSummaryGenerator summaryGenerator = new RecordingStructuredSummaryGenerator();
        RecordingTracePublisher tracePublisher = new RecordingTracePublisher();
        RecordingModelAdapter modelAdapter = new RecordingModelAdapter(List.of(new FinalModelResponse("继续完成任务")));
        String largePrompt = "分析超长上下文：" + "A".repeat(20_000);

        AgentLoopResult result = new AgentLoop(
                new ToolRegistry(),
                AgentLoopObserver.noop(),
                tracePublisher,
                new ContextCompactor(new ContextCompactionPolicy(10_000, 8, 1, 5_000, 800, 700)),
                messages -> messages.stream().anyMatch(message -> message.role() == MessageRole.CONTEXT_SUMMARY) ? 900 : 4_001,
                summaryGenerator
        ).run(new AgentLoopRequest(
                modelAdapter,
                List.of(new ConversationMessage(MessageRole.USER, largePrompt)),
                2
        ));

        List<ConversationMessage> firstModelCall = modelAdapter.calls().get(0);

        assertEquals("继续完成任务", result.finalAnswer());
        assertEquals(1, summaryGenerator.requests().size());
        assertTrue(firstModelCall.stream()
                .filter(message -> message.role() == MessageRole.CONTEXT_SUMMARY)
                .findFirst()
                .orElseThrow()
                .content()
                .contains("model_context_summary"));
        assertFalse(firstModelCall.stream().anyMatch(message -> message.content().contains("A".repeat(200))));
        TracePublisher.TraceEvent completed = tracePublisher.events().stream()
                .filter(event -> event.type() == TraceEventType.CONTEXT_COMPACTION_COMPLETED)
                .findFirst()
                .orElseThrow();
        assertEquals("true", completed.metadata().get("structuredSummaryApplied"));
        assertEquals("1", completed.metadata().get("compactedMessageCount"));
    }

    @Test
    void shouldRecordActualArchivedMessageCountWhenStructuredSummaryKeepsRecentWindow() {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register("echo", "回显输入文本", arguments -> ToolExecutionResult.success(arguments.get("text")));

        RecordingStructuredSummaryGenerator summaryGenerator = new RecordingStructuredSummaryGenerator();
        RecordingModelAdapter modelAdapter = new RecordingModelAdapter(List.of(
                new ToolCallModelResponse(List.of(new ToolCall("call-1", "echo", Map.of("text", "X".repeat(2_000))))),
                new FinalModelResponse("完成")
        ));

        new AgentLoop(
                toolRegistry,
                AgentLoopObserver.noop(),
                new RecordingTracePublisher(),
                new ContextCompactor(new ContextCompactionPolicy(100, 2, 1, 500, 10, 10)),
                messages -> messages.stream().anyMatch(message -> message.role() == MessageRole.TOOL) ? 1_000 : 10,
                summaryGenerator
        ).run(new AgentLoopRequest(
                modelAdapter,
                List.of(new ConversationMessage(MessageRole.USER, "读取长输出并继续")),
                4
        ));

        String contextSummary = modelAdapter.calls().get(1).stream()
                .filter(message -> message.role() == MessageRole.CONTEXT_SUMMARY)
                .findFirst()
                .orElseThrow()
                .content();

        assertTrue(contextSummary.contains("- archived_message_count: 1"));
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

    private static final class RecordingStructuredSummaryGenerator implements StructuredContextSummaryGenerator {

        private final List<StructuredContextSummaryRequest> requests = new ArrayList<>();

        @Override
        public StructuredContextSummary generate(StructuredContextSummaryRequest request) {
            requests.add(request);
            return new StructuredContextSummary(
                    "分析超长上下文",
                    "EXECUTE",
                    "已进入执行阶段",
                    List.of("README.md"),
                    List.of("旧历史已压缩为结构化摘要"),
                    List.of(),
                    List.of("规则压缩不足时使用模型摘要"),
                    List.of("继续完成任务")
            );
        }

        private List<StructuredContextSummaryRequest> requests() {
            return requests;
        }
    }
}
