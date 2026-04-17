package com.repopilot.cli.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.agent.AgentLoopObserver;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import com.repopilot.protocol.session.CreateSessionRequest;
import com.repopilot.protocol.session.SessionStatus;
import com.repopilot.protocol.session.SessionSummary;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InteractiveCliSessionTest {

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
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("/reset"));
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("/exit"));
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

    private static final class RecordingRuntimeRunner implements InteractiveRuntimeRunner {

        private final AtomicInteger createInitialHistoryCount = new AtomicInteger();
        private final AtomicInteger runTurnCount = new AtomicInteger();
        private final List<String> prompts = new ArrayList<>();
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
                AgentLoopObserver observer
        ) {
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
    }
}
