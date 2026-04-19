package com.repopilot.cli.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.cli.runtime.CliRuntimeBootstrap;
import com.repopilot.core.agent.AgentLoopObserver;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.approval.ToolApprovalHandler;
import com.repopilot.core.tool.ToolDefinition;
import com.repopilot.core.skill.SkillLoader;
import com.repopilot.protocol.session.SessionStatus;
import com.repopilot.protocol.session.SessionSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InteractiveRuntimeRunnerTest {

    @TempDir
    Path tempRoot;

    @Test
    void shouldCreateInitialHistoryWithTwoSystemMessages() {
        DefaultInteractiveRuntimeRunner runtimeRunner = new DefaultInteractiveRuntimeRunner(
                Clock.fixed(Instant.parse("2026-04-16T07:00:00Z"), ZoneOffset.UTC),
                new CliRuntimeBootstrap.EnvironmentBackedModelAdapterFactory(Map.of()),
                8
        );

        List<ConversationMessage> initialHistory = runtimeRunner.createInitialHistory(session());

        assertEquals(2, initialHistory.size());
        assertEquals(MessageRole.SYSTEM, initialHistory.get(0).role());
        assertEquals(MessageRole.SYSTEM, initialHistory.get(1).role());
        assertTrue(initialHistory.get(0).content().contains("# 基础指令"));
        assertTrue(initialHistory.get(1).content().contains("# 运行时上下文"));
    }

    @Test
    void shouldReuseHistoryAcrossTurns() {
        RecordingModelAdapterFactory modelAdapterFactory = new RecordingModelAdapterFactory();
        DefaultInteractiveRuntimeRunner runtimeRunner = new DefaultInteractiveRuntimeRunner(
                Clock.fixed(Instant.parse("2026-04-16T07:00:00Z"), ZoneOffset.UTC),
                modelAdapterFactory,
                8
        );

        List<ConversationMessage> initialHistory = runtimeRunner.createInitialHistory(session());
        InteractiveTurnResult firstTurn = runtimeRunner.runTurn(
                session(),
                initialHistory,
                "第一问",
                AgentLoopObserver.noop()
        );
        InteractiveTurnResult secondTurn = runtimeRunner.runTurn(
                session(),
                firstTurn.messages(),
                "第二问",
                AgentLoopObserver.noop()
        );

        assertEquals("回答: 第一问", firstTurn.finalAnswer());
        assertEquals("回答: 第二问", secondTurn.finalAnswer());
        assertTrue(modelAdapterFactory.recordedCalls.get(1).stream()
                .anyMatch(message -> message.role() == MessageRole.USER && message.content().equals("第一问")));
        assertTrue(modelAdapterFactory.recordedCalls.get(1).stream()
                .anyMatch(message -> message.role() == MessageRole.ASSISTANT && message.content().equals("回答: 第一问")));
        assertTrue(secondTurn.messages().stream()
                .anyMatch(message -> message.role() == MessageRole.USER && message.content().equals("第二问")));
    }

    @Test
    void shouldActivateSkillIntoHistoryWithoutCallingModel() throws Exception {
        Path workspaceRoot = tempRoot.resolve("workspace");
        Path userHome = tempRoot.resolve("home");
        writeSkill(
                workspaceRoot.resolve(".repopilot/skills/debug"),
                """
                        ---
                        name: debug
                        description: 结构化排查问题
                        ---
                        """,
                "## Debug Skill\n先复现，再缩小范围。\n"
        );
        RecordingModelAdapterFactory modelAdapterFactory = new RecordingModelAdapterFactory();
        DefaultInteractiveRuntimeRunner runtimeRunner = new DefaultInteractiveRuntimeRunner(
                Clock.fixed(Instant.parse("2026-04-16T07:00:00Z"), ZoneOffset.UTC),
                new com.repopilot.core.prompt.SystemPromptBuilder(),
                modelAdapterFactory,
                workspaceRoot,
                8,
                ToolApprovalHandler.denyAll(),
                SkillLoader.createDefault(workspaceRoot, userHome)
        );

        List<ConversationMessage> initialHistory = runtimeRunner.createInitialHistory(session());
        InteractiveTurnResult result = runtimeRunner.activateSkill(
                session(),
                initialHistory,
                "debug"
        );

        assertEquals("Skill debug 已激活。", result.finalAnswer());
        assertTrue(result.messages().stream()
                .filter(message -> message.role() == MessageRole.SYSTEM)
                .anyMatch(message -> message.content().contains("# Activated Skill")));
        assertEquals(0, modelAdapterFactory.recordedCalls.size());
    }

    private static SessionSummary session() {
        return new SessionSummary(
                "session-001",
                "workspace-001",
                "cli",
                SessionStatus.CREATED,
                Instant.parse("2026-04-16T06:59:00Z"),
                Instant.parse("2026-04-16T06:59:00Z")
        );
    }

    private static final class RecordingModelAdapterFactory implements CliRuntimeBootstrap.ModelAdapterFactory {

        private final List<List<ConversationMessage>> recordedCalls = new ArrayList<>();

        @Override
        public ModelAdapter create(SessionSummary sessionSummary, List<ToolDefinition> availableTools) {
            return new ModelAdapter() {
                @Override
                public ModelResponse next(List<ConversationMessage> messages) {
                    recordedCalls.add(List.copyOf(messages));
                    String latestPrompt = messages.stream()
                            .filter(message -> message.role() == MessageRole.USER)
                            .reduce((first, second) -> second)
                            .orElseThrow()
                            .content();
                    return new FinalModelResponse("回答: " + latestPrompt);
                }
            };
        }
    }

    private void writeSkill(Path skillRoot, String frontMatter, String body) throws Exception {
        Files.createDirectories(skillRoot);
        Files.writeString(skillRoot.resolve("SKILL.md"), frontMatter + body);
    }
}
