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
import com.repopilot.protocol.session.SessionStatus;
import com.repopilot.protocol.session.SessionSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CliRuntimeBootstrapTest {

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
                List.of("read_file", "grep_files", "run_command"),
                promptBuilder.capturedContext.availableTools().stream().map(tool -> tool.name()).toList()
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
}
