package com.repopilot.cli.eval;

import com.repopilot.cli.runtime.CliModelConfig;
import com.repopilot.cli.runtime.OpenAiCompatibleChatModelAdapter;
import com.repopilot.core.context.ContextCompactionPolicy;
import com.repopilot.core.model.FinalModelResponse;
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
        return List.of(realLongFileReadScenario(safeModelConfig));
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
                12,
                NO_COMPACTION_POLICY,
                STRUCTURED_COMPACTION_POLICY,
                ContextCostScenarioFactory::writeLongReadFixture,
                (workspace, strategy) -> createRealModelAdapter(modelConfig, workspace),
                execution -> requireFinalAnswerContains(execution, "6"),
                longReadExpectedFacts("请严格按顺序完成：")
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

    private static void writeLongReadFixture(Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve("notes"));
        Files.writeString(workspace.resolve("notes/a.txt"), "A".repeat(3_000));
        Files.writeString(workspace.resolve("notes/b.txt"), "B".repeat(3_000));
        Files.writeString(workspace.resolve("notes/c.txt"), "C".repeat(3_000));
        Files.writeString(workspace.resolve("notes/d.txt"), "D".repeat(3_000));
        Files.writeString(workspace.resolve("notes/e.txt"), "E".repeat(3_000));
        Files.writeString(workspace.resolve("notes/f.txt"), "F".repeat(3_000));
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
        return new OpenAiCompatibleChatModelAdapter(
                modelConfig.apiKey(),
                modelConfig.baseUrl(),
                modelConfig.modelName(),
                toolRegistry.list()
        );
    }

    private static void requireFinalAnswerContains(ContextCostScenario.ScenarioExecution execution, String expectedText) {
        String finalAnswer = execution.agentLoopResult().finalAnswer();
        if (finalAnswer == null || !finalAnswer.contains(expectedText)) {
            throw new IllegalStateException("最终回答不符合预期: " + finalAnswer);
        }
    }

    private static CliModelConfig requireRealModelConfig(CliModelConfig modelConfig) {
        CliModelConfig safeModelConfig = Objects.requireNonNull(modelConfig, "modelConfig must not be null.");
        if (!"openai-compatible".equals(safeModelConfig.provider())) {
            throw new IllegalArgumentException("REAL_USAGE context-cost 评测要求 REPOPILOT_MODEL_PROVIDER=openai-compatible。");
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
