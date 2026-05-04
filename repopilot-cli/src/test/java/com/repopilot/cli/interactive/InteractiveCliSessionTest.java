package com.repopilot.cli.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.cli.runtime.CliRuntimeBootstrap;
import com.repopilot.core.agent.AgentLoopObserver;
import com.repopilot.core.memory.FilePersistentMemoryStore;
import com.repopilot.core.memory.PersistentMemoryStore;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import com.repopilot.core.trace.TracePublisher;
import com.repopilot.core.tool.ToolDefinition;
import com.repopilot.protocol.session.CreateSessionRequest;
import com.repopilot.protocol.session.SessionStatus;
import com.repopilot.protocol.session.SessionSummary;
import com.repopilot.protocol.trace.AppendTraceEventRequest;
import com.repopilot.protocol.trace.TraceEventRecord;
import com.repopilot.protocol.trace.TraceEventType;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InteractiveCliSessionTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void shouldCreateSessionRunPromptAndExit() {
        RecordingSessionApiClient sessionApiClient = new RecordingSessionApiClient(List.of(
                session("session-001", "workspace-001")
        ));
        RecordingRuntimeRunner runtimeRunner = new RecordingRuntimeRunner();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintWriter outputWriter = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);

        InteractiveCliSession session = new InteractiveCliSession(
                new BufferedReader(new StringReader("分析 README.md\n/exit\n")),
                outputWriter,
                sessionApiClient,
                new InteractiveCliConfig("http://127.0.0.1:8080", "workspace-001"),
                runtimeRunner,
                new ConsoleTraceObserver(outputWriter)
        );

        session.start();

        assertEquals(1, sessionApiClient.createSessionCount.get());
        assertEquals(1, runtimeRunner.runTurnCount.get());
        assertEquals("分析 README.md", runtimeRunner.prompts.get(0));
        assertEquals("workspace-001", sessionApiClient.requests.get(0).workspaceId());
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("[session] created session-001 workspace=workspace-001"));
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("[assistant] 分析完成: 分析 README.md"));
    }

    @Test
    void shouldForwardInteractiveRuntimeTraceToControlPlane() {
        RecordingSessionApiClient sessionApiClient = new RecordingSessionApiClient(List.of(
                session("session-001", "workspace-001")
        ));
        RecordingTraceApiClient traceApiClient = new RecordingTraceApiClient();
        RecordingRuntimeRunner runtimeRunner = new RecordingRuntimeRunner();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintWriter outputWriter = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);

        InteractiveCliSession session = new InteractiveCliSession(
                new InteractiveLineInput(new BufferedReader(new StringReader("分析 README.md\n/exit\n"))),
                outputWriter,
                sessionApiClient,
                traceApiClient,
                new InteractiveCliConfig("http://127.0.0.1:8080", "workspace-001"),
                runtimeRunner,
                new ConsoleTraceObserver(outputWriter)
        );

        session.start();

        assertEquals(List.of("session-001"), traceApiClient.sessionIds);
        assertEquals(TraceEventType.MODEL_CALL_REQUESTED, traceApiClient.requests.get(0).type());
        assertEquals("cli", traceApiClient.requests.get(0).source());
    }

    @Test
    void shouldPrintHelpWithoutCallingRuntimeRunner() {
        RecordingSessionApiClient sessionApiClient = new RecordingSessionApiClient(List.of(
                session("session-001", "workspace-001")
        ));
        RecordingRuntimeRunner runtimeRunner = new RecordingRuntimeRunner();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintWriter outputWriter = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);

        InteractiveCliSession session = new InteractiveCliSession(
                new BufferedReader(new StringReader("/help\n/exit\n")),
                outputWriter,
                sessionApiClient,
                new InteractiveCliConfig("http://127.0.0.1:8080", "workspace-001"),
                runtimeRunner,
                new ConsoleTraceObserver(outputWriter)
        );

        session.start();

        assertEquals(0, runtimeRunner.runTurnCount.get());
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("/help"));
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("/plan"));
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("/execute"));
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("/remember"));
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("/memories"));
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("/memory <id>"));
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("/forget <id>"));
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("/reset"));
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("/exit"));
    }

    @Test
    void shouldRunPlanCommandInPlanModeAndExecuteCommandInExecuteMode() {
        RecordingSessionApiClient sessionApiClient = new RecordingSessionApiClient(List.of(
                session("session-001", "workspace-001")
        ));
        RecordingRuntimeRunner runtimeRunner = new RecordingRuntimeRunner();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintWriter outputWriter = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);

        InteractiveCliSession session = new InteractiveCliSession(
                new BufferedReader(new StringReader("/plan 分析修改方案\n/execute\n执行修改\n/exit\n")),
                outputWriter,
                sessionApiClient,
                new InteractiveCliConfig("http://127.0.0.1:8080", "workspace-001"),
                runtimeRunner,
                new ConsoleTraceObserver(outputWriter)
        );

        session.start();

        assertEquals(List.of("分析修改方案", "执行修改"), runtimeRunner.prompts);
        assertEquals(List.of(InteractionMode.PLAN, InteractionMode.EXECUTE), runtimeRunner.recordedModes);
        String output = outputStream.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[mode] PLAN"));
        assertTrue(output.contains("[mode] EXECUTE"));
    }

    @Test
    void shouldResetSessionAndClearHistory() {
        RecordingSessionApiClient sessionApiClient = new RecordingSessionApiClient(List.of(
                session("session-001", "workspace-001"),
                session("session-002", "workspace-001")
        ));
        RecordingRuntimeRunner runtimeRunner = new RecordingRuntimeRunner();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintWriter outputWriter = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);

        InteractiveCliSession session = new InteractiveCliSession(
                new BufferedReader(new StringReader("/reset\n/exit\n")),
                outputWriter,
                sessionApiClient,
                new InteractiveCliConfig("http://127.0.0.1:8080", "workspace-001"),
                runtimeRunner,
                new ConsoleTraceObserver(outputWriter)
        );

        session.start();

        assertEquals(2, sessionApiClient.createSessionCount.get());
        assertEquals(2, runtimeRunner.createInitialHistoryCount.get());
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("[session] created session-002 workspace=workspace-001"));
    }

    @Test
    void shouldKeepReplAliveWhenSingleTurnFails() {
        RecordingSessionApiClient sessionApiClient = new RecordingSessionApiClient(List.of(
                session("session-001", "workspace-001")
        ));
        RecordingRuntimeRunner runtimeRunner = new RecordingRuntimeRunner();
        runtimeRunner.failFirstRun = true;
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintWriter outputWriter = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);

        InteractiveCliSession session = new InteractiveCliSession(
                new BufferedReader(new StringReader("第一次\n第二次\n/exit\n")),
                outputWriter,
                sessionApiClient,
                new InteractiveCliConfig("http://127.0.0.1:8080", "workspace-001"),
                runtimeRunner,
                new ConsoleTraceObserver(outputWriter)
        );

        session.start();

        assertEquals(2, runtimeRunner.runTurnCount.get());
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("[error] 模拟失败"));
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("[assistant] 分析完成: 第二次"));
    }

    @Test
    void shouldActivateSkillOnlyWithoutRunningTurnWhenInputContainsOnlySkillCommand() {
        RecordingSessionApiClient sessionApiClient = new RecordingSessionApiClient(List.of(
                session("session-001", "workspace-001")
        ));
        RecordingRuntimeRunner runtimeRunner = new RecordingRuntimeRunner();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintWriter outputWriter = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);

        InteractiveCliSession session = new InteractiveCliSession(
                new BufferedReader(new StringReader("/debug\n/exit\n")),
                outputWriter,
                sessionApiClient,
                new InteractiveCliConfig("http://127.0.0.1:8080", "workspace-001"),
                runtimeRunner,
                new ConsoleTraceObserver(outputWriter)
        );

        session.start();

        assertEquals(1, runtimeRunner.activateSkillCount.get());
        assertEquals(0, runtimeRunner.runTurnCount.get());
        assertEquals(List.of("debug"), runtimeRunner.activatedSkillNames);
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("[assistant] Skill debug 已激活。"));
    }

    @Test
    void shouldActivateSkillAndContinueRunningRemainingPrompt() {
        RecordingSessionApiClient sessionApiClient = new RecordingSessionApiClient(List.of(
                session("session-001", "workspace-001")
        ));
        RecordingRuntimeRunner runtimeRunner = new RecordingRuntimeRunner();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintWriter outputWriter = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);

        InteractiveCliSession session = new InteractiveCliSession(
                new BufferedReader(new StringReader("/debug 修复这个测试\n/exit\n")),
                outputWriter,
                sessionApiClient,
                new InteractiveCliConfig("http://127.0.0.1:8080", "workspace-001"),
                runtimeRunner,
                new ConsoleTraceObserver(outputWriter)
        );

        session.start();

        assertEquals(1, runtimeRunner.activateSkillCount.get());
        assertEquals(1, runtimeRunner.runTurnCount.get());
        assertEquals(List.of("debug"), runtimeRunner.activatedSkillNames);
        assertEquals("修复这个测试", runtimeRunner.prompts.get(0));
        assertTrue(runtimeRunner.recordedHistories.get(0).stream()
                .filter(message -> message.role() == MessageRole.SYSTEM)
                .anyMatch(message -> message.content().contains("# Activated Skill")));
        assertFalse(outputStream.toString(StandardCharsets.UTF_8).contains("[assistant] Skill debug 已激活。"));
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("[assistant] 分析完成: 修复这个测试"));
    }

    @Test
    void shouldApproveWriteFileInsideInteractiveSessionAndPersistFile() throws Exception {
        RecordingSessionApiClient sessionApiClient = new RecordingSessionApiClient(List.of(
                session("session-010", "workspace-001")
        ));
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintWriter outputWriter = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);
        InteractiveLineInput sharedInput = new InteractiveLineInput(
                new BufferedReader(new StringReader("请写入文件\ny\n/exit\n"))
        );
        DefaultInteractiveRuntimeRunner runtimeRunner = new DefaultInteractiveRuntimeRunner(
                java.time.Clock.fixed(Instant.parse("2026-04-16T07:00:00Z"), java.time.ZoneOffset.UTC),
                new ScriptedWriteFileModelAdapterFactory(),
                workspaceRoot,
                8,
                new TerminalApprovalHandler(sharedInput, outputWriter)
        );

        InteractiveCliSession session = new InteractiveCliSession(
                sharedInput,
                outputWriter,
                sessionApiClient,
                new InteractiveCliConfig("http://127.0.0.1:8080", "workspace-001"),
                runtimeRunner,
                new ConsoleTraceObserver(outputWriter)
        );

        session.start();

        assertEquals("交互审批写入\n", Files.readString(workspaceRoot.resolve("docs/approved.txt")));
        String output = outputStream.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[approval] tool=write_file"));
        assertTrue(output.contains("DIFF_REVIEW"));
        assertTrue(output.contains("Approve? [y/N]:"));
        assertTrue(output.contains("[tool] write_file path=docs/approved.txt"));
        assertTrue(output.contains("[assistant] 写入完成"));
    }

    @Test
    void shouldKeepApprovalPendingUntilExplicitYesOrNo() throws Exception {
        RecordingSessionApiClient sessionApiClient = new RecordingSessionApiClient(List.of(
                session("session-011", "workspace-001")
        ));
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintWriter outputWriter = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);
        InteractiveLineInput sharedInput = new InteractiveLineInput(
                new BufferedReader(new StringReader("请修改示例文件\n2. 把审批结果改成“用户在终端输入 y 后通过”\ny\n/exit\n"))
        );
        DefaultInteractiveRuntimeRunner runtimeRunner = new DefaultInteractiveRuntimeRunner(
                java.time.Clock.fixed(Instant.parse("2026-04-16T07:00:00Z"), java.time.ZoneOffset.UTC),
                new ScriptedWriteFileModelAdapterFactory(),
                workspaceRoot,
                8,
                new TerminalApprovalHandler(sharedInput, outputWriter)
        );

        InteractiveCliSession session = new InteractiveCliSession(
                sharedInput,
                outputWriter,
                sessionApiClient,
                new InteractiveCliConfig("http://127.0.0.1:8080", "workspace-001"),
                runtimeRunner,
                new ConsoleTraceObserver(outputWriter)
        );

        session.start();

        assertEquals("交互审批写入\n", Files.readString(workspaceRoot.resolve("docs/approved.txt")));
        String output = outputStream.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[approval] 当前正在等待审批，请输入 y/yes 或 n/no。"));
        assertTrue(output.contains("[assistant] 写入完成"));
        assertFalse(output.contains("[user] 2. 把审批结果改成“用户在终端输入 y 后通过”"));
    }

    @Test
    void shouldRememberMemoryWithoutRunningModelTurn() {
        RecordingSessionApiClient sessionApiClient = new RecordingSessionApiClient(List.of(
                session("session-020", "workspace-001")
        ));
        RecordingRuntimeRunner runtimeRunner = new RecordingRuntimeRunner();
        PersistentMemoryStore memoryStore = new FilePersistentMemoryStore(workspaceRoot);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintWriter outputWriter = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);

        InteractiveCliSession session = new InteractiveCliSession(
                new BufferedReader(new StringReader("""
                        /remember
                        project
                        Plan 与 Execute 必须分阶段
                        该仓库要求先只读取证，再进入修改与验证。
                        在 RepoPilot 中，PLAN 阶段只允许只读工具，EXECUTE 阶段才允许修改与验证。
                        workflow,runtime
                        /exit
                        """)),
                outputWriter,
                sessionApiClient,
                new InteractiveCliConfig("http://127.0.0.1:8080", "workspace-001"),
                runtimeRunner,
                new ConsoleTraceObserver(outputWriter),
                new UserSkillCommandParser(),
                memoryStore
        );

        session.start();

        assertEquals(0, runtimeRunner.runTurnCount.get());
        assertTrue(memoryStore.get("plan-execute").isPresent());
        assertTrue(memoryStore.list().stream()
                .anyMatch(entry -> entry.title().equals("Plan 与 Execute 必须分阶段")));
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("[assistant] 已保存记忆"));
    }

    @Test
    void shouldForgetMemoryWithoutRunningModelTurn() {
        RecordingSessionApiClient sessionApiClient = new RecordingSessionApiClient(List.of(
                session("session-021", "workspace-001")
        ));
        RecordingRuntimeRunner runtimeRunner = new RecordingRuntimeRunner();
        PersistentMemoryStore memoryStore = new FilePersistentMemoryStore(workspaceRoot);
        memoryStore.save(new com.repopilot.core.memory.MemoryRecord(
                "project-plan-execute-boundary",
                com.repopilot.core.memory.MemoryType.PROJECT,
                "Plan 与 Execute 必须分阶段",
                "该仓库要求先只读取证，再进入修改与验证。",
                "在 RepoPilot 中，PLAN 阶段只允许只读工具，EXECUTE 阶段才允许修改与验证。",
                Instant.parse("2026-05-04T10:00:00Z"),
                Instant.parse("2026-05-04T10:00:00Z"),
                List.of("workflow", "runtime")
        ));
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintWriter outputWriter = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);

        InteractiveCliSession session = new InteractiveCliSession(
                new BufferedReader(new StringReader("/forget project-plan-execute-boundary\n/exit\n")),
                outputWriter,
                sessionApiClient,
                new InteractiveCliConfig("http://127.0.0.1:8080", "workspace-001"),
                runtimeRunner,
                new ConsoleTraceObserver(outputWriter),
                new UserSkillCommandParser(),
                memoryStore
        );

        session.start();

        assertEquals(0, runtimeRunner.runTurnCount.get());
        assertTrue(memoryStore.get("project-plan-execute-boundary").isEmpty());
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("[assistant] 已删除记忆 project-plan-execute-boundary"));
    }

    private static SessionSummary session(String sessionId, String workspaceId) {
        return new SessionSummary(
                sessionId,
                workspaceId,
                "cli",
                SessionStatus.CREATED,
                Instant.parse("2026-04-16T14:00:00Z"),
                Instant.parse("2026-04-16T14:00:00Z")
        );
    }

    private static final class RecordingSessionApiClient implements InteractiveCliSession.SessionClient {

        private final List<SessionSummary> scriptedSessions;
        private final AtomicInteger createSessionCount = new AtomicInteger();
        private final List<CreateSessionRequest> requests = new ArrayList<>();

        private RecordingSessionApiClient(List<SessionSummary> scriptedSessions) {
            this.scriptedSessions = scriptedSessions;
        }

        @Override
        public SessionSummary createSession(CreateSessionRequest request) {
            requests.add(request);
            int index = createSessionCount.getAndIncrement();
            return scriptedSessions.get(index);
        }
    }

    private static final class RecordingTraceApiClient implements InteractiveCliSession.TraceClient {

        private final List<String> sessionIds = new ArrayList<>();
        private final List<AppendTraceEventRequest> requests = new ArrayList<>();

        @Override
        public TraceEventRecord appendTraceEvent(String sessionId, AppendTraceEventRequest request) {
            sessionIds.add(sessionId);
            requests.add(request);
            return new TraceEventRecord(
                    "trace-001",
                    sessionId,
                    request.type(),
                    request.source(),
                    request.summary(),
                    request.occurredAt(),
                    request.metadata()
            );
        }
    }

    private static final class RecordingRuntimeRunner implements InteractiveRuntimeRunner {

        private final AtomicInteger createInitialHistoryCount = new AtomicInteger();
        private final AtomicInteger activateSkillCount = new AtomicInteger();
        private final AtomicInteger runTurnCount = new AtomicInteger();
        private final List<String> prompts = new ArrayList<>();
        private final List<String> activatedSkillNames = new ArrayList<>();
        private final List<List<ConversationMessage>> recordedHistories = new ArrayList<>();
        private final List<InteractionMode> recordedModes = new ArrayList<>();
        private boolean failFirstRun;

        @Override
        public List<ConversationMessage> createInitialHistory(SessionSummary sessionSummary) {
            createInitialHistoryCount.incrementAndGet();
            return List.of(new ConversationMessage(MessageRole.SYSTEM, "固定 system"));
        }

        @Override
        public InteractiveTurnResult runTurn(
                SessionSummary sessionSummary,
                List<ConversationMessage> history,
                String prompt,
                AgentLoopObserver observer,
                TracePublisher tracePublisher
        ) {
            return runTurn(sessionSummary, history, prompt, observer, tracePublisher, InteractionMode.EXECUTE);
        }

        @Override
        public InteractiveTurnResult runTurn(
                SessionSummary sessionSummary,
                List<ConversationMessage> history,
                String prompt,
                AgentLoopObserver observer,
                TracePublisher tracePublisher,
                InteractionMode interactionMode
        ) {
            tracePublisher.publish(new TracePublisher.TraceEvent(
                    TraceEventType.MODEL_CALL_REQUESTED,
                    "测试 trace",
                    Instant.parse("2026-04-16T14:00:01Z"),
                    Map.of("stepNumber", "1")
            ));
            recordedHistories.add(List.copyOf(history));
            recordedModes.add(interactionMode);
            prompts.add(prompt);
            int invocation = runTurnCount.incrementAndGet();
            if (failFirstRun && invocation == 1) {
                throw new IllegalStateException("模拟失败");
            }

            List<ConversationMessage> nextHistory = new ArrayList<>(history);
            nextHistory.add(new ConversationMessage(MessageRole.USER, prompt));
            nextHistory.add(new ConversationMessage(MessageRole.ASSISTANT, "分析完成: " + prompt));
            return new InteractiveTurnResult(nextHistory, "分析完成: " + prompt);
        }

        @Override
        public InteractiveTurnResult activateSkill(
                SessionSummary sessionSummary,
                List<ConversationMessage> history,
                String skillName
        ) {
            activateSkillCount.incrementAndGet();
            activatedSkillNames.add(skillName);

            List<ConversationMessage> nextHistory = new ArrayList<>(history);
            nextHistory.add(new ConversationMessage(
                    MessageRole.SYSTEM,
                    "# Activated Skill\nname: " + skillName + "\nsource: project\n\n## Skill\n正文"
            ));
            return new InteractiveTurnResult(nextHistory, "Skill " + skillName + " 已激活。");
        }
    }

    private static final class ScriptedWriteFileModelAdapterFactory implements CliRuntimeBootstrap.ModelAdapterFactory {

        @Override
        public ModelAdapter create(SessionSummary sessionSummary, List<ToolDefinition> availableTools) {
            return new ModelAdapter() {

                private int cursor;

                @Override
                public ModelResponse next(List<ConversationMessage> messages) {
                    if (cursor == 0) {
                        cursor += 1;
                        return new ToolCallModelResponse(List.of(
                                new ToolCall(
                                        "call-1",
                                        "write_file",
                                        Map.of(
                                                "path", "docs/approved.txt",
                                                "content", "交互审批写入\n"
                                        )
                                )
                        ));
                    }
                    return new FinalModelResponse("写入完成");
                }
            };
        }
    }

}
