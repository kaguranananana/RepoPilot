package com.repopilot.cli.interactive;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.protocol.session.SessionStatus;
import com.repopilot.protocol.session.SessionSummary;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsoleTraceObserverTest {

    @Test
    void shouldPrintDeterministicSummariesForSessionStepToolAndFinalAnswer() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintWriter outputWriter = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);
        ConsoleTraceObserver observer = new ConsoleTraceObserver(outputWriter);

        observer.onSessionCreated(new SessionSummary(
                "session-001",
                "workspace-001",
                "cli",
                SessionStatus.CREATED,
                Instant.parse("2026-04-16T06:59:00Z"),
                Instant.parse("2026-04-16T06:59:00Z")
        ));
        observer.onUserPrompt("读取 README.md");
        observer.onModelResponse(1, new ToolCallModelResponse(List.of(
                new ToolCall("call-1", "read_file", Map.of("path", "README.md"))
        )));
        observer.onToolExecutionStarted(1, new ToolCall("call-1", "read_file", Map.of("path", "README.md")));
        observer.onToolExecutionFinished(
                1,
                new ToolCall("call-1", "read_file", Map.of("path", "README.md")),
                ToolExecutionResult.success("第一行\n第二行\n")
        );
        observer.onModelResponse(2, new FinalModelResponse("文件内容已确认"));
        observer.onAssistantAnswer("文件内容已确认");

        String output = outputStream.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[session] created session-001 workspace=workspace-001"));
        assertTrue(output.contains("[user] 读取 README.md"));
        assertTrue(output.contains("[step 1] model -> tool_calls(1)"));
        assertTrue(output.contains("[tool] read_file path=README.md"));
        assertTrue(output.contains("[tool:success] read_file 2 行"));
        assertTrue(output.contains("[step 2] model -> final"));
        assertTrue(output.contains("[assistant] 文件内容已确认"));
    }

    @Test
    void shouldSummarizeRunCommandExitCodeAndErrors() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintWriter outputWriter = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);
        ConsoleTraceObserver observer = new ConsoleTraceObserver(outputWriter);

        observer.onToolExecutionStarted(1, new ToolCall("call-1", "run_command", Map.of("command", "mvn test")));
        observer.onToolExecutionFinished(
                1,
                new ToolCall("call-1", "run_command", Map.of("command", "mvn test")),
                ToolExecutionResult.recoverableError("exitCode: 1\nstdout:\nok\nstderr:\nboom")
        );
        observer.onError("模拟失败");

        String output = outputStream.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[tool] run_command command=mvn test"));
        assertTrue(output.contains("[tool:error] run_command exitCode=1"));
        assertTrue(output.contains("[error] 模拟失败"));
    }

    @Test
    void shouldPrintGrepFilesFatalErrorInsteadOfFakeHitCount() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintWriter outputWriter = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);
        ConsoleTraceObserver observer = new ConsoleTraceObserver(outputWriter);

        observer.onToolExecutionStarted(1, new ToolCall("call-1", "grep_files", Map.of("pattern", "@Command\\(")));
        observer.onToolExecutionFinished(
                1,
                new ToolCall("call-1", "grep_files", Map.of("pattern", "@Command\\(")),
                ToolExecutionResult.fatalError("搜索文件失败: Input length = 1")
        );

        String output = outputStream.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[tool:fatal] grep_files 搜索文件失败: Input length = 1"));
    }

    @Test
    void shouldPrintVerboseMessageFlowForToolRoundTrip() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintWriter outputWriter = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);
        ConsoleTraceObserver observer = new ConsoleTraceObserver(outputWriter, TraceLevel.VERBOSE);

        List<ConversationMessage> stepOneMessages = List.of(
                new ConversationMessage(MessageRole.SYSTEM, "system prompt"),
                new ConversationMessage(MessageRole.SYSTEM, "runtime context"),
                new ConversationMessage(MessageRole.USER, "读取 README.md")
        );
        observer.onStepStarted(1, stepOneMessages);
        observer.onModelResponse(1, new ToolCallModelResponse(List.of(
                new ToolCall("call-1", "read_file", Map.of("path", "README.md"))
        )));
        observer.onToolExecutionStarted(1, new ToolCall("call-1", "read_file", Map.of("path", "README.md")));
        observer.onToolExecutionFinished(
                1,
                new ToolCall("call-1", "read_file", Map.of("path", "README.md")),
                ToolExecutionResult.success("第一行\n第二行\n")
        );
        observer.onToolMessageAdded(
                1,
                new ToolCall("call-1", "read_file", Map.of("path", "README.md")),
                ConversationMessage.toolResult("call-1", "[read_file] 第一行\n第二行\n")
        );
        observer.onStepStarted(2, List.of(
                new ConversationMessage(MessageRole.SYSTEM, "system prompt"),
                new ConversationMessage(MessageRole.SYSTEM, "runtime context"),
                new ConversationMessage(MessageRole.USER, "读取 README.md"),
                ConversationMessage.assistantToolCalls(List.of(
                        new ToolCall("call-1", "read_file", Map.of("path", "README.md"))
                )),
                ConversationMessage.toolResult("call-1", "[read_file] 第一行\n第二行\n")
        ));

        String output = outputStream.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[step 1] messages (3 total)"));
        assertTrue(output.contains("[message 1] SYSTEM"));
        assertTrue(output.contains("content preview:\n      system prompt"));
        assertTrue(output.contains("[message 3] USER"));
        assertTrue(output.contains("content preview:\n      读取 README.md"));
        assertTrue(output.contains("[tool_call 1]"));
        assertTrue(output.contains("id: call-1"));
        assertTrue(output.contains("tool: read_file"));
        assertTrue(output.contains("arguments:\n      path: README.md"));
        assertTrue(output.contains("[tool_message]"));
        assertTrue(output.contains("toolCallId: call-1"));
        assertTrue(output.contains("content preview:\n      [read_file] 第一行\n      第二行"));
        assertTrue(output.contains("[step 2] messages (5 total)"));
        assertTrue(output.contains("[message 4] ASSISTANT tool_calls"));
        assertTrue(output.contains("toolCalls:\n      call-1 -> read_file"));
        assertTrue(output.contains("[message 5] TOOL"));
        assertTrue(output.contains("toolCallId: call-1\n    content preview:\n      [read_file] 第一行\n      第二行"));
    }
}
