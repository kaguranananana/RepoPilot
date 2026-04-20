package com.repopilot.core.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.repopilot.core.agent.AgentLoop;
import com.repopilot.core.agent.AgentLoopRequest;
import com.repopilot.core.agent.AgentLoopResult;
import com.repopilot.core.agent.loop.ToolCallLoopDetectedException;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolRegistry;
import com.repopilot.protocol.trace.TraceEventType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TracePublishingAgentLoopTest {

    @Test
    void shouldPublishModelAndToolTraceAcrossSuccessfulRound() {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register("echo", "回显输入文本", arguments -> ToolExecutionResult.success(arguments.get("text")));

        RecordingTracePublisher tracePublisher = new RecordingTracePublisher();
        ModelAdapter modelAdapter = new ScriptedModelAdapter(List.of(
                new ToolCallModelResponse(List.of(new ToolCall("call-1", "echo", Map.of("text", "pom.xml")))),
                new FinalModelResponse("分析完成")
        ));

        AgentLoopResult result = new AgentLoop(toolRegistry, tracePublisher).run(new AgentLoopRequest(
                modelAdapter,
                List.of(new ConversationMessage(MessageRole.USER, "读取 pom.xml")),
                4
        ));

        assertEquals("分析完成", result.finalAnswer());
        assertEquals(
                List.of(
                        TraceEventType.MODEL_CALL_REQUESTED,
                        TraceEventType.MODEL_RESPONSE_RECEIVED,
                        TraceEventType.TOOL_CALL_REQUESTED,
                        TraceEventType.TOOL_CALL_COMPLETED,
                        TraceEventType.MODEL_CALL_REQUESTED,
                        TraceEventType.MODEL_RESPONSE_RECEIVED
                ),
                tracePublisher.events().stream().map(TracePublisher.TraceEvent::type).toList()
        );
        assertEquals("1", tracePublisher.events().get(0).metadata().get("stepNumber"));
        assertEquals("TOOL_CALLS", tracePublisher.events().get(1).metadata().get("responseKind"));
        assertEquals("call-1", tracePublisher.events().get(2).metadata().get("toolCallId"));
        assertEquals("SUCCESS", tracePublisher.events().get(3).metadata().get("toolStatus"));
        assertEquals("FINAL", tracePublisher.events().get(5).metadata().get("responseKind"));
    }

    @Test
    void shouldPublishRecoverableToolErrorStatusAndContinueLoop() {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(
                "read_file",
                "读取文件",
                arguments -> ToolExecutionResult.recoverableError("文件不存在: " + arguments.get("path"))
        );

        RecordingTracePublisher tracePublisher = new RecordingTracePublisher();
        ModelAdapter modelAdapter = new ScriptedModelAdapter(List.of(
                new ToolCallModelResponse(List.of(new ToolCall("call-1", "read_file", Map.of("path", "missing.txt")))),
                new FinalModelResponse("请检查文件路径后重试")
        ));

        AgentLoopResult result = new AgentLoop(toolRegistry, tracePublisher).run(new AgentLoopRequest(
                modelAdapter,
                List.of(new ConversationMessage(MessageRole.USER, "读取 missing.txt")),
                4
        ));

        assertEquals("请检查文件路径后重试", result.finalAnswer());
        assertEquals("RECOVERABLE_ERROR", tracePublisher.events().get(3).metadata().get("toolStatus"));
        assertEquals(6, tracePublisher.events().size());
    }

    @Test
    void shouldPublishFatalToolErrorStatusBeforeInterruptingMainLoop() {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(
                "write_file",
                "写入文件",
                arguments -> ToolExecutionResult.fatalError("权限拒绝: " + arguments.get("path"))
        );

        RecordingTracePublisher tracePublisher = new RecordingTracePublisher();
        ModelAdapter modelAdapter = new ScriptedModelAdapter(List.of(
                new ToolCallModelResponse(List.of(new ToolCall("call-1", "write_file", Map.of("path", "/root/secret.txt"))))
        ));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new AgentLoop(toolRegistry, tracePublisher).run(new AgentLoopRequest(
                        modelAdapter,
                        List.of(new ConversationMessage(MessageRole.USER, "写入 /root/secret.txt")),
                        4
                ))
        );

        assertEquals("Tool execution failed fatally: write_file, output=权限拒绝: /root/secret.txt", exception.getMessage());
        assertEquals(
                List.of(
                        TraceEventType.MODEL_CALL_REQUESTED,
                        TraceEventType.MODEL_RESPONSE_RECEIVED,
                        TraceEventType.TOOL_CALL_REQUESTED,
                        TraceEventType.TOOL_CALL_COMPLETED
                ),
                tracePublisher.events().stream().map(TracePublisher.TraceEvent::type).toList()
        );
        assertEquals("FATAL_ERROR", tracePublisher.events().get(3).metadata().get("toolStatus"));
    }

    @Test
    void shouldPublishLoopDetectedTraceBeforeInterruptingMainLoop() {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register("read_file", "读取文件", arguments -> ToolExecutionResult.success("文件内容"));

        RecordingTracePublisher tracePublisher = new RecordingTracePublisher();
        ModelAdapter modelAdapter = new ScriptedModelAdapter(List.of(
                new ToolCallModelResponse(List.of(new ToolCall("call-1", "read_file", Map.of("path", "README.md")))),
                new ToolCallModelResponse(List.of(new ToolCall("call-2", "read_file", Map.of("path", "README.md")))),
                new ToolCallModelResponse(List.of(new ToolCall("call-3", "read_file", Map.of("path", "README.md"))))
        ));

        ToolCallLoopDetectedException exception = assertThrows(
                ToolCallLoopDetectedException.class,
                () -> new AgentLoop(toolRegistry, tracePublisher).run(new AgentLoopRequest(
                        modelAdapter,
                        List.of(new ConversationMessage(MessageRole.USER, "读取 README.md")),
                        5,
                        3
                ))
        );

        assertEquals(
                "连续重复工具调用导致终止: toolName=read_file, repeatCount=3, arguments=path=README.md",
                exception.getMessage()
        );
        assertEquals(11, tracePublisher.events().size());
        TracePublisher.TraceEvent loopEvent = tracePublisher.events().get(10);
        assertEquals(TraceEventType.TOOL_CALL_LOOP_DETECTED, loopEvent.type());
        assertEquals("3", loopEvent.metadata().get("stepNumber"));
        assertEquals("read_file", loopEvent.metadata().get("toolName"));
        assertEquals("3", loopEvent.metadata().get("repeatCount"));
        assertEquals("path=README.md", loopEvent.metadata().get("argumentsSummary"));
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
