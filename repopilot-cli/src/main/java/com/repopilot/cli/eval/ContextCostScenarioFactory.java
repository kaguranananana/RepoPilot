package com.repopilot.cli.eval;

import com.repopilot.cli.runtime.AnthropicChatModelAdapter;
import com.repopilot.cli.runtime.CliModelConfig;
import com.repopilot.cli.runtime.OpenAiCompatibleChatModelAdapter;
import com.repopilot.core.context.ContextCompactionPolicy;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import com.repopilot.core.skill.SkillLoader;
import com.repopilot.core.tool.ToolRegistry;
import com.repopilot.core.tool.builtin.BuiltinToolRegistrar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * context-cost 默认场景工厂。
 * scripted 场景用于本地估算，real-model 场景用于读取 provider 返回的真实 usage。
 */
public final class ContextCostScenarioFactory {

    private static final ContextCompactionPolicy NO_COMPACTION_POLICY =
            new ContextCompactionPolicy(10_000, 9_999, 100);
    private static final ContextCompactionPolicy STRUCTURED_COMPACTION_POLICY =
            new ContextCompactionPolicy(10_000, 8, 1, 5_000, 800, 700);

    private ContextCostScenarioFactory() {
    }

    public static List<ContextCostScenario> defaultEstimatedScenarios() {
        return List.of(scriptedLongFileReadScenario());
    }

    public static List<ContextCostScenario> defaultRealUsageScenarios(CliModelConfig modelConfig) {
        CliModelConfig safeModelConfig = requireRealModelConfig(modelConfig);
        return List.of(
                realLongFileReadScenario(safeModelConfig),
                realSearchReadBatchScenario(safeModelConfig),
                realSpecReviewReadScenario(safeModelConfig)
        );
    }

    private static ContextCostScenario scriptedLongFileReadScenario() {
        return new ContextCostScenario(
                "long-file-read",
                "长文件读取",
                "请连续读取 notes/a.txt 到 notes/f.txt，最后汇报已读文件数量。",
                10,
                NO_COMPACTION_POLICY,
                STRUCTURED_COMPACTION_POLICY,
                ContextCostScenarioFactory::writeLongReadFixture,
                (workspace, strategy) -> scriptedModel(List.of(
                        readTool("call-a", "notes/a.txt"),
                        readTool("call-b", "notes/b.txt"),
                        readTool("call-c", "notes/c.txt"),
                        readTool("call-d", "notes/d.txt"),
                        readTool("call-e", "notes/e.txt"),
                        readTool("call-f", "notes/f.txt"),
                        new FinalModelResponse("已读取 6 个文件")
                )),
                execution -> requireFinalAnswerContains(execution, "6"),
                longReadExpectedFacts("请连续读取 notes/a.txt 到 notes/f.txt，最后汇报已读文件数量。")
        );
    }

    private static ContextCostScenario realLongFileReadScenario(CliModelConfig modelConfig) {
        return new ContextCostScenario(
                "long-file-read",
                "真实模型长文件读取",
                """
                        请严格按顺序完成：
                        1) 依次使用 read_file 读取 notes/a.txt、notes/b.txt、notes/c.txt、notes/d.txt、notes/e.txt、notes/f.txt；
                        2) 不要搜索，不要运行命令，不要修改文件；
                        3) 最后用一句话汇报已读取 6 个文件。
                        """,
                20,
                NO_COMPACTION_POLICY,
                STRUCTURED_COMPACTION_POLICY,
                ContextCostScenarioFactory::writeLongReadFixture,
                (workspace, strategy) -> createRealModelAdapter(modelConfig, workspace),
                execution -> requireFinalAnswerContains(execution, "6"),
                longReadExpectedFacts("请严格按顺序完成：")
        );
    }

    private static ContextCostScenario realSearchReadBatchScenario(CliModelConfig modelConfig) {
        return new ContextCostScenario(
                "batch-read",
                "真实模型多文档批量读取",
                """
                        请严格按顺序完成：
                        1) 依次使用 read_file 读取 notes/alpha.md、notes/beta.md、notes/gamma.md、notes/delta.md、notes/epsilon.md、notes/zeta.md；
                        2) 不要搜索，不要运行命令，不要修改文件；
                        3) 最后用一句话汇报已读取 6 个批量文档。
                        """,
                18,
                NO_COMPACTION_POLICY,
                STRUCTURED_COMPACTION_POLICY,
                ContextCostScenarioFactory::writeSearchReadBatchFixture,
                (workspace, strategy) -> createRealModelAdapter(modelConfig, workspace),
                execution -> {
                    // 这个场景只验证固定三文件的连续读取，
                    // 尽量减少工具选择分岔带来的模型波动，
                    // 让观测重点集中在压缩前后的 token 差异。
                    requireReadFileEvidence(execution, "notes/alpha.md", "# Alpha Batch");
                    requireReadFileEvidence(execution, "notes/beta.md", "# Beta Batch");
                    requireReadFileEvidence(execution, "notes/gamma.md", "# Gamma Batch");
                    requireReadFileEvidence(execution, "notes/delta.md", "# Delta Batch");
                    requireReadFileEvidence(execution, "notes/epsilon.md", "# Epsilon Batch");
                    requireReadFileEvidence(execution, "notes/zeta.md", "# Zeta Batch");
                    requireNonBlankFinalAnswer(execution);
                },
                searchReadBatchExpectedFacts()
        );
    }

    private static ContextCostScenario realSpecReviewReadScenario(CliModelConfig modelConfig) {
        return new ContextCostScenario(
                "spec-review-read",
                "真实模型多规格阅读",
                """
                        请严格按顺序完成：
                        1) 依次使用 read_file 读取 specs/runtime.md、specs/context.md、specs/skills.md、specs/tools.md、specs/tracing.md；
                        2) 不要搜索，不要运行命令，不要修改文件；
                        3) 最后用一句话汇报已读取 5 份说明文档。
                        """,
                18,
                NO_COMPACTION_POLICY,
                STRUCTURED_COMPACTION_POLICY,
                ContextCostScenarioFactory::writeSpecReviewFixture,
                (workspace, strategy) -> createRealModelAdapter(modelConfig, workspace),
                execution -> {
                    // 这个场景不依赖搜索或修改能力，
                    // 只验证长文档连续读取是否完成，
                    // 便于把观测重点聚焦到上下文压缩收益本身。
                    requireReadFileEvidence(execution, "specs/runtime.md", "# Runtime Spec");
                    requireReadFileEvidence(execution, "specs/context.md", "# Context Spec");
                    requireReadFileEvidence(execution, "specs/skills.md", "# Skills Spec");
                    requireReadFileEvidence(execution, "specs/tools.md", "# Tools Spec");
                    requireReadFileEvidence(execution, "specs/tracing.md", "# Tracing Spec");
                    requireNonBlankFinalAnswer(execution);
                },
                specReviewExpectedFacts()
        );
    }

    private static List<ContextCostFactExpectation> longReadExpectedFacts(String promptText) {
        return List.of(
                new ContextCostFactExpectation("goal", "用户目标", promptText),
                new ContextCostFactExpectation("file-a", "已读文件 A", "notes/a.txt"),
                new ContextCostFactExpectation("file-b", "已读文件 B", "notes/b.txt"),
                new ContextCostFactExpectation("file-c", "已读文件 C", "notes/c.txt"),
                new ContextCostFactExpectation("file-d", "已读文件 D", "notes/d.txt"),
                new ContextCostFactExpectation("file-e", "已读文件 E", "notes/e.txt"),
                new ContextCostFactExpectation("file-f", "已读文件 F", "notes/f.txt")
        );
    }

    private static List<ContextCostFactExpectation> searchReadBatchExpectedFacts() {
        return List.of(
                new ContextCostFactExpectation("alpha", "批量文档 Alpha", "notes/alpha.md"),
                new ContextCostFactExpectation("beta", "批量文档 Beta", "notes/beta.md"),
                new ContextCostFactExpectation("gamma", "批量文档 Gamma", "notes/gamma.md"),
                new ContextCostFactExpectation("delta", "批量文档 Delta", "notes/delta.md"),
                new ContextCostFactExpectation("epsilon", "批量文档 Epsilon", "notes/epsilon.md"),
                new ContextCostFactExpectation("zeta", "批量文档 Zeta", "notes/zeta.md")
        );
    }

    private static List<ContextCostFactExpectation> specReviewExpectedFacts() {
        return List.of(
                new ContextCostFactExpectation("runtime", "运行时规格", "# Runtime Spec"),
                new ContextCostFactExpectation("context", "上下文规格", "# Context Spec"),
                new ContextCostFactExpectation("skills", "技能规格", "# Skills Spec"),
                new ContextCostFactExpectation("tools", "工具规格", "# Tools Spec"),
                new ContextCostFactExpectation("tracing", "追踪规格", "# Tracing Spec"),
                new ContextCostFactExpectation("runtime-file", "运行时规格文件", "specs/runtime.md"),
                new ContextCostFactExpectation("context-file", "上下文规格文件", "specs/context.md"),
                new ContextCostFactExpectation("skills-file", "技能规格文件", "specs/skills.md"),
                new ContextCostFactExpectation("tools-file", "工具规格文件", "specs/tools.md"),
                new ContextCostFactExpectation("tracing-file", "追踪规格文件", "specs/tracing.md")
        );
    }

    private static void writeLongReadFixture(Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve("notes"));
        Files.writeString(workspace.resolve("notes/a.txt"), "A".repeat(3_000));
        Files.writeString(workspace.resolve("notes/b.txt"), "B".repeat(3_000));
        Files.writeString(workspace.resolve("notes/c.txt"), "C".repeat(3_000));
        Files.writeString(workspace.resolve("notes/d.txt"), "D".repeat(3_000));
        Files.writeString(workspace.resolve("notes/e.txt"), "E".repeat(3_000));
        Files.writeString(workspace.resolve("notes/f.txt"), "F".repeat(3_000));
    }

    private static void writeSearchReadBatchFixture(Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve("notes"));
        writeLargeMarkdown(
                workspace.resolve("notes/alpha.md"),
                "# Alpha Batch",
                "context-batch-marker-20260421",
                "ALPHA",
                1_800
        );
        writeLargeMarkdown(
                workspace.resolve("notes/beta.md"),
                "# Beta Batch",
                "context-batch-marker-20260421",
                "BETA",
                1_800
        );
        writeLargeMarkdown(
                workspace.resolve("notes/gamma.md"),
                "# Gamma Batch",
                "context-batch-marker-20260421",
                "GAMMA",
                1_800
        );
        writeLargeMarkdown(
                workspace.resolve("notes/delta.md"),
                "# Delta Batch",
                "context-batch-marker-20260421",
                "DELTA",
                1_800
        );
        writeLargeMarkdown(
                workspace.resolve("notes/epsilon.md"),
                "# Epsilon Batch",
                "context-batch-marker-20260421",
                "EPSILON",
                1_800
        );
        writeLargeMarkdown(
                workspace.resolve("notes/zeta.md"),
                "# Zeta Batch",
                "context-batch-marker-20260421",
                "ZETA",
                1_800
        );
    }

    private static void writeSpecReviewFixture(Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve("specs"));
        writeLargeMarkdown(
                workspace.resolve("specs/runtime.md"),
                "# Runtime Spec",
                "runtime-mode=execute",
                "RUNTIME",
                1_700
        );
        writeLargeMarkdown(
                workspace.resolve("specs/context.md"),
                "# Context Spec",
                "context-layer=summary",
                "CONTEXT",
                1_700
        );
        writeLargeMarkdown(
                workspace.resolve("specs/skills.md"),
                "# Skills Spec",
                "skill-mode=allowed-tools",
                "SKILLS",
                1_700
        );
        writeLargeMarkdown(
                workspace.resolve("specs/tools.md"),
                "# Tools Spec",
                "tool-governance=approval",
                "TOOLS",
                1_700
        );
        writeLargeMarkdown(
                workspace.resolve("specs/tracing.md"),
                "# Tracing Spec",
                "trace-mode=audit",
                "TRACING",
                1_700
        );
    }

    private static void writeLargeMarkdown(
            Path filePath,
            String title,
            String marker,
            String repeatedToken,
            int repeatCount
    ) throws IOException {
        // 这里把正文长度控制在“能稳定触发压缩、但不会把真实评测成本拉得过高”的区间。
        String content = title
                + "\n"
                + "marker=" + marker + "\n"
                + (repeatedToken + " ").repeat(repeatCount)
                + "\n";
        Files.writeString(filePath, content);
    }

    private static ToolCallModelResponse readTool(String id, String path) {
        return new ToolCallModelResponse(List.of(new ToolCall(id, "read_file", Map.of("path", path))));
    }

    private static ModelAdapter scriptedModel(List<ModelResponse> responses) {
        return new ScriptedModelAdapter(responses);
    }

    private static ModelAdapter createRealModelAdapter(CliModelConfig modelConfig, Path workspaceRoot) {
        ToolRegistry toolRegistry = new ToolRegistry();
        BuiltinToolRegistrar.registerAll(
                toolRegistry,
                workspaceRoot,
                SkillLoader.createDefault(workspaceRoot, workspaceRoot.resolve("home"))
        );
        return switch (modelConfig.provider()) {
            case "openai-compatible" -> new OpenAiCompatibleChatModelAdapter(
                    modelConfig.apiKey(),
                    modelConfig.baseUrl(),
                    modelConfig.modelName(),
                    toolRegistry.list()
            );
            case "anthropic" -> new AnthropicChatModelAdapter(
                    modelConfig.apiKey(),
                    modelConfig.baseUrl(),
                    modelConfig.modelName(),
                    toolRegistry.list()
            );
            default -> throw new IllegalStateException("Unsupported real model provider: " + modelConfig.provider());
        };
    }

    private static void requireFinalAnswerContains(ContextCostScenario.ScenarioExecution execution, String expectedText) {
        String finalAnswer = execution.agentLoopResult().finalAnswer();
        if (finalAnswer == null || !finalAnswer.contains(expectedText)) {
            throw new IllegalStateException("最终回答不符合预期: " + finalAnswer);
        }
    }

    private static void requireNonBlankFinalAnswer(ContextCostScenario.ScenarioExecution execution) {
        String finalAnswer = execution.agentLoopResult().finalAnswer();
        if (finalAnswer == null || finalAnswer.isBlank()) {
            throw new IllegalStateException("最终回答不能为空。");
        }
    }

    private static void requireReadFileEvidence(
            ContextCostScenario.ScenarioExecution execution,
            String expectedPath,
            String expectedContent
    ) {
        boolean found = execution.agentLoopResult().messages().stream()
                .filter(message -> message.role() == MessageRole.TOOL)
                .anyMatch(message -> message.content().contains(expectedContent)
                        || message.content().contains("path=" + expectedPath));
        if (!found) {
            throw new IllegalStateException("未观察到 read_file 结果证据: " + expectedPath);
        }
    }

    private static CliModelConfig requireRealModelConfig(CliModelConfig modelConfig) {
        CliModelConfig safeModelConfig = Objects.requireNonNull(modelConfig, "modelConfig must not be null.");
        if (!"openai-compatible".equals(safeModelConfig.provider())
                && !"anthropic".equals(safeModelConfig.provider())) {
            throw new IllegalArgumentException(
                    "REAL_USAGE context-cost 评测要求 REPOPILOT_MODEL_PROVIDER 为 openai-compatible 或 anthropic。"
            );
        }
        return safeModelConfig;
    }

    private static final class ScriptedModelAdapter implements ModelAdapter {

        private final List<ModelResponse> responses;
        private int cursor;

        private ScriptedModelAdapter(List<ModelResponse> responses) {
            this.responses = List.copyOf(responses);
        }

        @Override
        public ModelResponse next(List<com.repopilot.core.model.ConversationMessage> messages) {
            if (cursor >= responses.size()) {
                throw new IllegalStateException("脚本模型响应已耗尽。");
            }
            ModelResponse response = responses.get(cursor);
            cursor += 1;
            return response;
        }
    }
}
