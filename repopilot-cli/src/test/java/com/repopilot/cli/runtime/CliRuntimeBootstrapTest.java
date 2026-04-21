package com.repopilot.cli.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.prompt.DynamicPromptContext;
import com.repopilot.core.prompt.SystemPromptBoundary;
import com.repopilot.core.prompt.SystemPromptBuilder;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import com.repopilot.core.skill.SkillLoader;
import com.repopilot.core.skill.SkillSummary;
import com.repopilot.core.trace.TracePublisher;
import com.repopilot.protocol.session.SessionStatus;
import com.repopilot.protocol.session.SessionSummary;
import com.repopilot.protocol.trace.TraceEventType;
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

class CliRuntimeBootstrapTest {

    @TempDir
    Path tempRoot;

    @Test
    void shouldExposeBuiltinToolsToDynamicPromptContext() {
        CapturingSystemPromptBuilder promptBuilder = new CapturingSystemPromptBuilder();
        CliRuntimeBootstrap bootstrap = new CliRuntimeBootstrap.DefaultCliRuntimeBootstrap(
                Clock.fixed(Instant.parse("2026-04-16T07:00:00Z"), ZoneOffset.UTC),
                promptBuilder,
                (sessionSummary, availableTools) -> new CliRuntimeBootstrap.BootstrapModelAdapter(sessionSummary)
        );

        String answer = bootstrap.run(
                new SessionSummary(
                        "session-001",
                        "workspace-001",
                        "cli",
                        SessionStatus.CREATED,
                        Instant.parse("2026-04-16T06:59:00Z"),
                        Instant.parse("2026-04-16T06:59:00Z")
                ),
                "分析 README.md"
        );

        assertEquals(
                "RepoPilot runtime accepted prompt for session session-001: 分析 README.md",
                answer
        );
        assertEquals(
                List.of("read_file", "grep_files", "activate_skill", "apply_patch", "write_file", "run_command"),
                promptBuilder.capturedContext.availableTools().stream().map(tool -> tool.name()).toList()
        );
    }

    @Test
    void shouldLoadSkillSummariesIntoDynamicPromptContext() throws Exception {
        Path workspaceRoot = tempRoot.resolve("workspace");
        Path userHome = tempRoot.resolve("home");
        writeSkill(
                workspaceRoot.resolve(".repopilot/skills/project-skill"),
                """
                        ---
                        name: project-skill
                        description: 项目级 Skill 摘要
                        allowed-tools:
                          - read_file
                        ---
                        """
        );
        writeSkill(
                userHome.resolve(".repopilot/skills/user-skill"),
                """
                        ---
                        name: user-skill
                        description: 用户级 Skill 摘要
                        ---
                        """
        );
        CapturingSystemPromptBuilder promptBuilder = new CapturingSystemPromptBuilder();
        CliRuntimeBootstrap bootstrap = new CliRuntimeBootstrap.DefaultCliRuntimeBootstrap(
                Clock.fixed(Instant.parse("2026-04-16T07:00:00Z"), ZoneOffset.UTC),
                promptBuilder,
                SkillLoader.forRoots(
                        List.of(workspaceRoot.resolve(".repopilot/skills")),
                        List.of(userHome.resolve(".repopilot/skills"))
                ),
                (sessionSummary, availableTools) -> new CliRuntimeBootstrap.BootstrapModelAdapter(sessionSummary)
        );

        bootstrap.run(
                new SessionSummary(
                        "session-003",
                        "workspace-001",
                        "cli",
                        SessionStatus.CREATED,
                        Instant.parse("2026-04-16T06:59:00Z"),
                        Instant.parse("2026-04-16T06:59:00Z")
                ),
                "列出可用 Skill"
        );

        assertEquals(
                List.of("project-skill", "user-skill"),
                promptBuilder.capturedContext.skillSummaries().stream().map(SkillSummary::name).toList()
        );
    }

    @Test
    void shouldCompleteToolCallingRoundTripWhenModelNeedsMoreThanOneStep() {
        CliRuntimeBootstrap bootstrap = new CliRuntimeBootstrap.DefaultCliRuntimeBootstrap(
                Clock.fixed(Instant.parse("2026-04-16T07:00:00Z"), ZoneOffset.UTC),
                new SystemPromptBuilder(),
                (sessionSummary, availableTools) -> new ScriptedModelAdapter(List.of(
                        new ToolCallModelResponse(List.of(new ToolCall("call-1", "read_file", Map.of("path", "pom.xml")))),
                        new FinalModelResponse("多步回合完成")
                ))
        );

        String answer = bootstrap.run(
                new SessionSummary(
                        "session-002",
                        "workspace-001",
                        "cli",
                        SessionStatus.CREATED,
                        Instant.parse("2026-04-16T06:59:00Z"),
                        Instant.parse("2026-04-16T06:59:00Z")
                ),
                "读取 pom.xml 后总结"
        );

        assertEquals("多步回合完成", answer);
    }

    @Test
    void shouldUseTokenBudgetCompactionInSingleRunRuntime() {
        RecordingTracePublisher tracePublisher = new RecordingTracePublisher();
        CliRuntimeBootstrap bootstrap = new CliRuntimeBootstrap.DefaultCliRuntimeBootstrap(
                Clock.fixed(Instant.parse("2026-04-16T07:00:00Z"), ZoneOffset.UTC),
                new SystemPromptBuilder(),
                (sessionSummary, availableTools) -> new CliRuntimeBootstrap.BootstrapModelAdapter(sessionSummary)
        );

        bootstrap.run(
                new SessionSummary(
                        "session-004",
                        "workspace-001",
                        "cli",
                        SessionStatus.CREATED,
                        Instant.parse("2026-04-16T06:59:00Z"),
                        Instant.parse("2026-04-16T06:59:00Z")
                ),
                longPrompt(),
                tracePublisher
        );

        assertEquals(
                List.of("TOKEN_BUDGET"),
                tracePublisher.events.stream()
                        .filter(event -> event.type() == TraceEventType.CONTEXT_COMPACTION_COMPLETED)
                        .map(event -> event.metadata().get("trigger"))
                        .toList()
        );
    }

    private static final class CapturingSystemPromptBuilder extends SystemPromptBuilder {

        private DynamicPromptContext capturedContext;

        @Override
        public SystemPromptBoundary build(DynamicPromptContext dynamicPromptContext) {
            this.capturedContext = dynamicPromptContext;
            return super.build(dynamicPromptContext);
        }
    }

    private static final class ScriptedModelAdapter implements ModelAdapter {

        private final List<ModelResponse> scriptedResponses;
        private int cursor;

        private ScriptedModelAdapter(List<ModelResponse> scriptedResponses) {
            this.scriptedResponses = scriptedResponses;
        }

        @Override
        public ModelResponse next(List<com.repopilot.core.model.ConversationMessage> messages) {
            ModelResponse response = scriptedResponses.get(cursor);
            cursor += 1;
            return response;
        }
    }

    private static final class RecordingTracePublisher implements TracePublisher {

        private final List<TraceEvent> events = new ArrayList<>();

        @Override
        public void publish(TraceEvent event) {
            events.add(event);
        }
    }

    private String longPrompt() {
        return "分析下面的长上下文：" + " token-budget".repeat(20_000);
    }

    private void writeSkill(Path skillRoot, String frontMatter) throws Exception {
        Files.createDirectories(skillRoot);
        Files.writeString(skillRoot.resolve("SKILL.md"), frontMatter + "## Skill\n正文\n");
    }
}
