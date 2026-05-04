package com.repopilot.cli.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.memory.FilePersistentMemoryStore;
import com.repopilot.core.memory.MemoryRecord;
import com.repopilot.core.memory.MemoryType;
import com.repopilot.core.memory.PersistentMemoryStore;
import com.repopilot.core.memory.RecalledMemoryPromptRenderer;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.prompt.DynamicPromptContext;
import com.repopilot.core.prompt.SystemPromptBoundary;
import com.repopilot.core.prompt.SystemPromptBuilder;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import com.repopilot.core.skill.SkillLoader;
import com.repopilot.core.skill.SkillSummary;
import com.repopilot.core.trace.TracePublisher;
import com.repopilot.protocol.session.SessionStatus;
import com.repopilot.protocol.session.SessionSummary;
import com.repopilot.protocol.trace.TraceEventType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

    @Test
    void shouldInjectRecalledMemoriesBeforeSingleRun() {
        Path workspaceRoot = tempRoot.resolve("workspace");
        Path userHome = tempRoot.resolve("home");
        PersistentMemoryStore memoryStore = new FilePersistentMemoryStore(workspaceRoot);
        memoryStore.save(new MemoryRecord(
                "project-plan-boundary",
                MemoryType.PROJECT,
                "Plan boundary",
                "先分析再修改",
                "PLAN 阶段只允许只读工具。",
                Instant.parse("2026-04-16T07:00:00Z"),
                Instant.parse("2026-04-16T07:00:00Z"),
                List.of("demo")
        ));
        RecordingBootstrapModelAdapterFactory modelAdapterFactory = new RecordingBootstrapModelAdapterFactory(
                """
                        {
                          "selected_ids": ["project-plan-boundary"]
                        }
                        """
        );
        CliRuntimeBootstrap bootstrap = new CliRuntimeBootstrap.DefaultCliRuntimeBootstrap(
                Clock.fixed(Instant.parse("2026-04-16T07:00:00Z"), ZoneOffset.UTC),
                new SystemPromptBuilder(),
                workspaceRoot,
                SkillLoader.createDefault(workspaceRoot, userHome),
                modelAdapterFactory,
                memoryStore,
                new RecalledMemoryPromptRenderer()
        );

        String answer = bootstrap.run(
                new SessionSummary(
                        "session-005",
                        "workspace-001",
                        "cli",
                        SessionStatus.CREATED,
                        Instant.parse("2026-04-16T06:59:00Z"),
                        Instant.parse("2026-04-16T06:59:00Z")
                ),
                "先分析改动方案"
        );

        assertEquals("回答: 先分析改动方案", answer);
        assertTrue(modelAdapterFactory.recordedRuntimeMessages.stream()
                .filter(message -> message.role() == com.repopilot.core.model.MessageRole.SYSTEM)
                .anyMatch(message -> message.content().contains("# Recalled Memories")));
        assertTrue(modelAdapterFactory.recordedRuntimeMessages.stream()
                .filter(message -> message.role() == com.repopilot.core.model.MessageRole.SYSTEM)
                .anyMatch(message -> message.content().contains("project-plan-boundary")));
    }

    @Test
    void shouldForceStructuredSummaryToolChoiceAndDisableThinkingForDeepSeekSummaryModel() throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        RecordingOpenAiCompatibleEndpoint endpoint = new RecordingOpenAiCompatibleEndpoint();
        httpServer.createContext("/chat/completions", endpoint::handle);
        httpServer.start();

        try {
            CliRuntimeBootstrap.EnvironmentBackedModelAdapterFactory factory =
                    new CliRuntimeBootstrap.EnvironmentBackedModelAdapterFactory(Map.of(
                            "REPOPILOT_MODEL_PROVIDER", "openai-compatible",
                            "OPENAI_COMPATIBLE_API_KEY", "test-key",
                            "OPENAI_COMPATIBLE_BASE_URL", "http://127.0.0.1:" + httpServer.getAddress().getPort(),
                            "OPENAI_COMPATIBLE_MODEL", "deepseek-v4-pro"
                    ));

            ModelAdapter summaryModel = factory.createContextSummaryModel(new SessionSummary(
                    "session-006",
                    "workspace-001",
                    "cli",
                    SessionStatus.CREATED,
                    Instant.parse("2026-04-16T06:59:00Z"),
                    Instant.parse("2026-04-16T06:59:00Z")
            ));
            summaryModel.next(List.of(
                    new ConversationMessage(com.repopilot.core.model.MessageRole.SYSTEM, "你是摘要模型。"),
                    new ConversationMessage(com.repopilot.core.model.MessageRole.USER, "请输出结构化摘要。")
            ));

            assertTrue(
                    endpoint.lastRequestBody.contains("\"tool_choice\":{\"type\":\"function\",\"function\":{\"name\":\"submit_structured_context_summary\"}}"),
                    "结构化摘要模型请求必须显式强制 submit_structured_context_summary 工具。"
            );
            assertTrue(
                    endpoint.lastRequestBody.contains("\"thinking\":{\"type\":\"disabled\"}"),
                    "DeepSeek 结构化摘要模型请求必须显式关闭 thinking，避免 tool_choice 在默认思考模式下被服务端拒绝。"
            );
        } finally {
            httpServer.stop(0);
        }
    }

    @Test
    void shouldNotInjectThinkingToggleForNonDeepSeekOpenAiCompatibleSummaryModel() throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        RecordingOpenAiCompatibleEndpoint endpoint = new RecordingOpenAiCompatibleEndpoint();
        httpServer.createContext("/chat/completions", endpoint::handle);
        httpServer.start();

        try {
            CliRuntimeBootstrap.EnvironmentBackedModelAdapterFactory factory =
                    new CliRuntimeBootstrap.EnvironmentBackedModelAdapterFactory(Map.of(
                            "REPOPILOT_MODEL_PROVIDER", "openai-compatible",
                            "OPENAI_COMPATIBLE_API_KEY", "test-key",
                            "OPENAI_COMPATIBLE_BASE_URL", "http://127.0.0.1:" + httpServer.getAddress().getPort(),
                            "OPENAI_COMPATIBLE_MODEL", "gpt-4.1"
                    ));

            ModelAdapter summaryModel = factory.createContextSummaryModel(new SessionSummary(
                    "session-007",
                    "workspace-001",
                    "cli",
                    SessionStatus.CREATED,
                    Instant.parse("2026-04-16T06:59:00Z"),
                    Instant.parse("2026-04-16T06:59:00Z")
            ));
            summaryModel.next(List.of(
                    new ConversationMessage(com.repopilot.core.model.MessageRole.SYSTEM, "你是摘要模型。"),
                    new ConversationMessage(com.repopilot.core.model.MessageRole.USER, "请输出结构化摘要。")
            ));

            assertFalse(
                    endpoint.lastRequestBody.contains("\"thinking\":"),
                    "非 DeepSeek 的 OpenAI 兼容模型不应被注入 DeepSeek 私有的 thinking 开关。"
            );
        } finally {
            httpServer.stop(0);
        }
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

    private static final class RecordingBootstrapModelAdapterFactory implements CliRuntimeBootstrap.ModelAdapterFactory {

        private final String selectorResponse;
        private List<com.repopilot.core.model.ConversationMessage> recordedRuntimeMessages = List.of();

        private RecordingBootstrapModelAdapterFactory(String selectorResponse) {
            this.selectorResponse = selectorResponse;
        }

        @Override
        public ModelAdapter create(SessionSummary sessionSummary, List<com.repopilot.core.tool.ToolDefinition> availableTools) {
            return messages -> {
                recordedRuntimeMessages = List.copyOf(messages);
                String latestPrompt = messages.stream()
                        .filter(message -> message.role() == com.repopilot.core.model.MessageRole.USER)
                        .reduce((first, second) -> second)
                        .orElseThrow()
                        .content();
                return new FinalModelResponse("回答: " + latestPrompt);
            };
        }

        @Override
        public ModelAdapter createContextSummaryModel(SessionSummary sessionSummary) {
            return messages -> new FinalModelResponse(selectorResponse);
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

    private static final class RecordingOpenAiCompatibleEndpoint {

        private String lastRequestBody = "";

        private void handle(HttpExchange exchange) throws IOException {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] responseBody = """
                    {
                      "id": "chatcmpl-summary",
                      "choices": [
                        {
                          "index": 0,
                          "message": {
                            "role": "assistant",
                            "content": "{\\"user_goal\\":\\"测试\\",\\"current_phase\\":\\"PLAN\\",\\"plan_state\\":\\"无\\",\\"touched_files\\":[],\\"important_findings\\":[],\\"failed_commands\\":[],\\"decisions\\":[],\\"next_actions\\":[]}"
                          }
                        }
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        }
    }
}
