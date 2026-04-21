package com.repopilot.cli.eval;

import com.repopilot.cli.runtime.AnthropicChatModelAdapter;
import com.repopilot.cli.runtime.CliModelConfig;
import com.repopilot.cli.runtime.OpenAiCompatibleChatModelAdapter;
import com.repopilot.core.agent.AgentLoopResult;
import com.repopilot.core.context.ContextCompactionPolicy;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import com.repopilot.core.skill.SkillLoader;
import com.repopilot.core.trace.TracePublisher;
import com.repopilot.core.tool.ToolRegistry;
import com.repopilot.core.tool.builtin.BuiltinToolRegistrar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 固定评估任务定义。
 * 场景只描述输入、夹具、脚本模型和验收条件，
 * 真正执行仍交给 EvalRunner 走统一 runtime 主链路。
 */
public record EvalScenario(
        String id,
        String title,
        RuntimeKind runtimeKind,
        String prompt,
        int maxSteps,
        ContextCompactionPolicy contextCompactionPolicy,
        WorkspaceInitializer workspaceInitializer,
        ModelAdapterFactory modelAdapterFactory,
        ScenarioVerifier scenarioVerifier
) {

    private static final Pattern SCENARIO_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]*");

    public EvalScenario {
        id = requireScenarioId(id);
        title = requireNonBlank(title, "title must not be blank.");
        runtimeKind = Objects.requireNonNull(runtimeKind, "runtimeKind must not be null.");
        prompt = requireNonBlank(prompt, "prompt must not be blank.");
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be greater than zero.");
        }
        contextCompactionPolicy =
                Objects.requireNonNull(contextCompactionPolicy, "contextCompactionPolicy must not be null.");
        workspaceInitializer =
                Objects.requireNonNull(workspaceInitializer, "workspaceInitializer must not be null.");
        modelAdapterFactory = Objects.requireNonNull(modelAdapterFactory, "modelAdapterFactory must not be null.");
        scenarioVerifier = Objects.requireNonNull(scenarioVerifier, "scenarioVerifier must not be null.");
    }

    public static EvalScenario scripted(
            String id,
            String title,
            String prompt,
            int maxSteps,
            WorkspaceInitializer workspaceInitializer,
            List<ModelResponse> scriptedResponses,
            ScenarioVerifier scenarioVerifier
    ) {
        return scripted(
                id,
                title,
                prompt,
                maxSteps,
                ContextCompactionPolicy.defaultPolicy(),
                workspaceInitializer,
                scriptedResponses,
                scenarioVerifier
        );
    }

    public static EvalScenario scripted(
            String id,
            String title,
            String prompt,
            int maxSteps,
            ContextCompactionPolicy contextCompactionPolicy,
            WorkspaceInitializer workspaceInitializer,
            List<ModelResponse> scriptedResponses,
            ScenarioVerifier scenarioVerifier
    ) {
        List<ModelResponse> safeScriptedResponses = List.copyOf(
                Objects.requireNonNull(scriptedResponses, "scriptedResponses must not be null.")
        );
        if (safeScriptedResponses.isEmpty()) {
            throw new IllegalArgumentException("scriptedResponses must not be empty.");
        }

        return new EvalScenario(
                id,
                title,
                RuntimeKind.SCRIPTED_RUNTIME,
                prompt,
                maxSteps,
                contextCompactionPolicy,
                workspaceInitializer,
                workspaceRoot -> new ScriptedModelAdapter(safeScriptedResponses),
                scenarioVerifier
        );
    }

    public static EvalScenario realModel(
            String id,
            String title,
            String prompt,
            int maxSteps,
            WorkspaceInitializer workspaceInitializer,
            CliModelConfig modelConfig,
            ScenarioVerifier scenarioVerifier
    ) {
        return realModel(
                id,
                title,
                prompt,
                maxSteps,
                ContextCompactionPolicy.defaultPolicy(),
                workspaceInitializer,
                modelConfig,
                scenarioVerifier
        );
    }

    public static EvalScenario realModel(
            String id,
            String title,
            String prompt,
            int maxSteps,
            ContextCompactionPolicy contextCompactionPolicy,
            WorkspaceInitializer workspaceInitializer,
            CliModelConfig modelConfig,
            ScenarioVerifier scenarioVerifier
    ) {
        CliModelConfig safeModelConfig = requireRealModelConfig(modelConfig);
        return new EvalScenario(
                id,
                title,
                RuntimeKind.REAL_MODEL_PROVIDER,
                prompt,
                maxSteps,
                contextCompactionPolicy,
                workspaceInitializer,
                workspaceRoot -> createRealModelAdapter(safeModelConfig, workspaceRoot),
                scenarioVerifier
        );
    }

    public static List<EvalScenario> defaultScriptedScenarios() {
        return List.of(
                codeSearchScenario(),
                fileReadScenario(),
                patchEditScenario(),
                commandValidationScenario(),
                skillActivationScenario(),
                readFailureExposureScenario(),
                contextCompactionScenario(),
                writeFileScenario(),
                multiToolScenario(),
                commandFailureExposureScenario()
        );
    }

    public static List<EvalScenario> defaultRealModelScenarios(CliModelConfig modelConfig) {
        CliModelConfig safeModelConfig = requireRealModelConfig(modelConfig);
        return List.of(
                realModelCodeSearchScenario(safeModelConfig),
                realModelFileReadScenario(safeModelConfig),
                realModelPatchEditScenario(safeModelConfig),
                realModelCommandValidationScenario(safeModelConfig),
                realModelSearchReadPatchCommandScenario(safeModelConfig)
        );
    }

    private static EvalScenario codeSearchScenario() {
        return scripted(
                "code-search",
                "代码搜索",
                "搜索 draft 状态配置",
                4,
                workspace -> writeFile(workspace, "src/Demo.txt", "name=RepoPilot\nstatus=draft\n"),
                List.of(
                        tool("call-search", "grep_files", Map.of("pattern", "status=draft", "path", "src")),
                        new FinalModelResponse("搜索完成")
                ),
                execution -> requireToolMessage(execution, "[grep_files]")
        );
    }

    private static EvalScenario fileReadScenario() {
        return scripted(
                "file-read",
                "文件读取",
                "读取 README",
                4,
                workspace -> writeFile(workspace, "README.md", "# RepoPilot\n"),
                List.of(
                        tool("call-read", "read_file", Map.of("path", "README.md")),
                        new FinalModelResponse("读取完成")
                ),
                execution -> requireToolMessage(execution, "# RepoPilot")
        );
    }

    private static EvalScenario patchEditScenario() {
        return scripted(
                "patch-edit",
                "补丁修改",
                "把状态改成 ready",
                5,
                workspace -> writeFile(workspace, "src/Demo.txt", "name=RepoPilot\nstatus=draft\n"),
                List.of(
                        tool("call-patch", "apply_patch", Map.of(
                                "path", "src/Demo.txt",
                                "patch", """
                                        @@
                                         name=RepoPilot
                                        -status=draft
                                        +status=ready
                                        """
                        )),
                        new FinalModelResponse("补丁完成")
                ),
                execution -> requireFileContent(execution.workspaceRoot(), "src/Demo.txt", "name=RepoPilot\nstatus=ready\n")
        );
    }

    private static EvalScenario commandValidationScenario() {
        return scripted(
                "command-validation",
                "命令验证",
                "用命令验证 ready 状态",
                4,
                workspace -> writeFile(workspace, "src/Demo.txt", "status=ready\n"),
                List.of(
                        tool("call-command", "run_command", Map.of("command", "grep -n 'status=ready' src/Demo.txt")),
                        new FinalModelResponse("命令验证完成")
                ),
                execution -> requireToolMessage(execution, "exitCode: 0")
        );
    }

    private static EvalScenario skillActivationScenario() {
        return scripted(
                "skill-activation",
                "Skill 激活",
                "激活 eval-skill",
                4,
                workspace -> writeFile(
                        workspace,
                        ".repopilot/skills/eval-skill/SKILL.md",
                        """
                                ---
                                name: eval-skill
                                description: 评估用 Skill
                                ---
                                # Eval Skill
                                """
                ),
                List.of(
                        tool("call-skill", "activate_skill", Map.of("name", "eval-skill")),
                        new FinalModelResponse("Skill 激活完成")
                ),
                execution -> requireToolMessage(execution, "eval-skill")
        );
    }

    private static EvalScenario readFailureExposureScenario() {
        return scripted(
                "read-failure-exposure",
                "工具失败暴露",
                "读取不存在文件并暴露错误",
                4,
                workspace -> {
                },
                List.of(
                        tool("call-missing-read", "read_file", Map.of("path", "missing.txt")),
                        new FinalModelResponse("文件不存在错误已暴露")
                ),
                execution -> requireToolMessage(execution, "文件不存在: missing.txt")
        );
    }

    private static EvalScenario contextCompactionScenario() {
        return scripted(
                "context-compaction",
                "上下文压缩",
                "连续读取文件直到触发上下文压缩",
                8,
                new ContextCompactionPolicy(4, 2, 2),
                workspace -> {
                    // 压缩场景需要制造多轮工具消息，但不能再连续重复同一个工具 key。
                    writeFile(workspace, "notes/a.txt", "A\n");
                    writeFile(workspace, "notes/b.txt", "B\n");
                    writeFile(workspace, "notes/c.txt", "C\n");
                },
                List.of(
                        tool("call-read-1", "read_file", Map.of("path", "notes/a.txt")),
                        tool("call-read-2", "read_file", Map.of("path", "notes/b.txt")),
                        tool("call-read-3", "read_file", Map.of("path", "notes/c.txt")),
                        new FinalModelResponse("压缩链路完成")
                ),
                execution -> {
                    boolean compacted = execution.traceEvents().stream()
                            .anyMatch(event -> event.type().name().equals("CONTEXT_COMPACTION_COMPLETED"));
                    if (!compacted) {
                        throw new IllegalStateException("未观察到上下文压缩完成 trace。");
                    }
                }
        );
    }

    private static EvalScenario writeFileScenario() {
        return scripted(
                "write-file",
                "整文件写入",
                "创建新文件",
                4,
                workspace -> {
                },
                List.of(
                        tool("call-write", "write_file", Map.of(
                                "path", "docs/output.md",
                                "content", "# 输出\nstatus=ready\n"
                        )),
                        new FinalModelResponse("写入完成")
                ),
                execution -> requireFileContent(execution.workspaceRoot(), "docs/output.md", "# 输出\nstatus=ready\n")
        );
    }

    private static EvalScenario multiToolScenario() {
        return scripted(
                "multi-tool-round",
                "单轮多工具",
                "同一轮搜索并读取文件",
                4,
                workspace -> writeFile(workspace, "src/App.java", "class App {}\n"),
                List.of(
                        new ToolCallModelResponse(List.of(
                                new ToolCall("call-multi-1", "grep_files", Map.of("pattern", "class App", "path", "src")),
                                new ToolCall("call-multi-2", "read_file", Map.of("path", "src/App.java"))
                        )),
                        new FinalModelResponse("单轮多工具完成")
                ),
                execution -> {
                    requireToolMessage(execution, "[grep_files]");
                    requireToolMessage(execution, "class App {}");
                }
        );
    }

    private static EvalScenario commandFailureExposureScenario() {
        return scripted(
                "command-failure-exposure",
                "命令失败暴露",
                "运行失败命令并暴露退出码",
                4,
                workspace -> {
                },
                List.of(
                        tool("call-failing-command", "run_command", Map.of("command", "test -f missing.txt")),
                        new FinalModelResponse("命令失败已暴露")
                ),
                execution -> requireToolMessage(execution, "exitCode: 1")
        );
    }

    private static EvalScenario realModelCodeSearchScenario(CliModelConfig modelConfig) {
        return realModel(
                "code-search",
                "真实模型代码搜索",
                "请只使用 grep_files 在 src 目录中搜索 `status=draft`，不要读取文件，不要运行命令，找到后简短汇报。",
                10,
                workspace -> writeFile(workspace, "src/Demo.txt", "name=RepoPilot\nstatus=draft\n"),
                modelConfig,
                execution -> {
                    requireToolMessage(execution, "[grep_files]");
                    requireToolMessage(execution, "status=draft");
                }
        );
    }

    private static EvalScenario realModelFileReadScenario(CliModelConfig modelConfig) {
        return realModel(
                "file-read",
                "真实模型文件读取",
                "请直接 read_file 读取 README.md，不要搜索，不要运行命令，最后简短汇报第一行内容。",
                10,
                workspace -> writeFile(workspace, "README.md", "# RepoPilot\n"),
                modelConfig,
                execution -> requireToolMessage(execution, "# RepoPilot")
        );
    }

    private static EvalScenario realModelPatchEditScenario(CliModelConfig modelConfig) {
        return realModel(
                "patch-edit",
                "真实模型补丁修改",
                """
                        请严格按顺序使用工具完成任务：
                        1) 直接 read_file 读取 src/Demo.txt，不要搜索，不要运行 find 或 ls；
                        2) 使用 apply_patch 在同一个 @@ hunk 中把 status=draft 替换为 status=ready，必须同时包含 -status=draft 和 +status=ready；
                        3) 运行 grep -n 'status=ready' src/Demo.txt 验证；
                        4) 简短汇报。
                        """,
                16,
                workspace -> writeFile(workspace, "src/Demo.txt", "name=RepoPilot\nstatus=draft\n"),
                modelConfig,
                execution -> {
                    requireToolMessage(execution, "PATCH_APPLY");
                    requireToolMessage(execution, "2:status=ready");
                    requireFileContent(execution.workspaceRoot(), "src/Demo.txt", "name=RepoPilot\nstatus=ready\n");
                }
        );
    }

    private static EvalScenario realModelCommandValidationScenario(CliModelConfig modelConfig) {
        return realModel(
                "command-validation",
                "真实模型命令验证",
                """
                        请按顺序完成任务：
                        1) 直接 read_file 读取 src/Demo.txt；
                        2) 运行 grep -n 'status=ready' src/Demo.txt 验证状态；
                        3) 不要修改文件；
                        4) 简短汇报。
                        """,
                10,
                workspace -> writeFile(workspace, "src/Demo.txt", "name=RepoPilot\nstatus=ready\n"),
                modelConfig,
                execution -> {
                    requireToolMessage(execution, "exitCode: 0");
                    requireToolMessage(execution, "2:status=ready");
                    requireFileContent(execution.workspaceRoot(), "src/Demo.txt", "name=RepoPilot\nstatus=ready\n");
                }
        );
    }

    private static EvalScenario realModelSearchReadPatchCommandScenario(CliModelConfig modelConfig) {
        return realModel(
                "search-read-patch-command",
                "真实模型端到端编码任务",
                """
                        请严格按顺序使用工具完成一次最小端到端编码任务：
                        1) 先只使用 grep_files 在 src 目录搜索 `repopilot-e2e-marker-20260420`，定位目标文件；
                        2) 再对命中的文件执行 read_file，确认当前内容；
                        3) 使用 apply_patch 只把 `status=draft` 改成 `status=ready`，除这一行外其他内容必须保持不变；
                        4) 补丁必须在同一个 @@ hunk 中同时包含上下文行 `name=RepoPilot`、`marker=repopilot-e2e-marker-20260420`、删除行 `-status=draft`、新增行 `+status=ready`；
                        5) 运行 `grep -n 'status=ready' src/EndToEndDemo.txt` 验证修改结果；
                        6) 最后用一句话汇报，不要再调用工具。
                        """,
                16,
                // 这个场景的验收需要读取完整 tool_call 参数链路，
                // 因此这里显式放宽消息窗口，避免默认压缩把前两步 tool_call 折叠掉。
                new ContextCompactionPolicy(16, 8, 4),
                workspace -> writeFile(
                        workspace,
                        "src/EndToEndDemo.txt",
                        """
                                name=RepoPilot
                                marker=repopilot-e2e-marker-20260420
                                status=draft
                                """
                ),
                modelConfig,
                execution -> {
                    requireToolCallOrder(
                            execution,
                            List.of("grep_files", "read_file", "apply_patch", "run_command")
                    );
                    requireToolArgumentEquals(execution, "grep_files", "pattern", "repopilot-e2e-marker-20260420");
                    requireToolArgumentEquals(execution, "grep_files", "path", "src");
                    requireToolArgumentEquals(execution, "read_file", "path", "src/EndToEndDemo.txt");
                    requireToolArgumentEquals(execution, "apply_patch", "path", "src/EndToEndDemo.txt");
                    requireToolArgumentContains(execution, "apply_patch", "patch", "name=RepoPilot");
                    requireToolArgumentContains(
                            execution,
                            "apply_patch",
                            "patch",
                            "marker=repopilot-e2e-marker-20260420"
                    );
                    requireToolArgumentContains(execution, "apply_patch", "patch", "-status=draft");
                    requireToolArgumentContains(execution, "apply_patch", "patch", "+status=ready");
                    requireToolArgumentEquals(
                            execution,
                            "run_command",
                            "command",
                            "grep -n 'status=ready' src/EndToEndDemo.txt"
                    );
                    requireToolMessage(execution, "PATCH_APPLY");
                    requireToolMessage(execution, "exitCode: 0");
                    requireFileContent(
                            execution.workspaceRoot(),
                            "src/EndToEndDemo.txt",
                            """
                                    name=RepoPilot
                                    marker=repopilot-e2e-marker-20260420
                                    status=ready
                                    """
                    );
                    requireFinalAnswerContains(execution, "ready");
                }
        );
    }

    private static ToolCallModelResponse tool(String id, String toolName, Map<String, String> arguments) {
        return new ToolCallModelResponse(List.of(new ToolCall(id, toolName, arguments)));
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

    private static void writeFile(Path workspaceRoot, String relativePath, String content) throws IOException {
        Path targetFile = workspaceRoot.resolve(relativePath);
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, content);
    }

    private static void requireFileContent(Path workspaceRoot, String relativePath, String expectedContent)
            throws IOException {
        String actualContent = Files.readString(workspaceRoot.resolve(relativePath));
        if (!actualContent.equals(expectedContent)) {
            throw new IllegalStateException("文件内容不符合预期: " + relativePath);
        }
    }

    private static void requireToolMessage(ScenarioExecution execution, String expectedText) {
        boolean found = execution.agentLoopResult().messages().stream()
                .anyMatch(message -> message.content().contains(expectedText));
        if (!found) {
            throw new IllegalStateException("未在工具消息中找到: " + expectedText);
        }
    }

    private static void requireToolCallOrder(ScenarioExecution execution, List<String> expectedToolNames) {
        // 先只保留真正执行完成的工具事件，
        // 避免把模型最终回答或其他非工具 trace 混进顺序校验里。
        List<String> actualToolNames = execution.traceEvents().stream()
                .filter(event -> event.type().name().equals("TOOL_CALL_COMPLETED"))
                // 每个 TOOL_CALL_COMPLETED 都带有 toolName 元数据，
                // 这里逐条取出，保留真实执行顺序。
                .map(event -> event.metadata().get("toolName"))
                .toList();
        if (!actualToolNames.equals(expectedToolNames)) {
            throw new IllegalStateException("工具调用顺序不符合预期: " + actualToolNames);
        }
    }

    private static void requireToolArgumentEquals(
            ScenarioExecution execution,
            String toolName,
            String argumentKey,
            String expectedValue
    ) {
        ToolCall toolCall = requireToolCall(execution, toolName);
        String actualValue = toolCall.arguments().get(argumentKey);
        if (!Objects.equals(actualValue, expectedValue)) {
            throw new IllegalStateException(
                    "工具参数不符合预期: %s.%s=%s".formatted(toolName, argumentKey, actualValue)
            );
        }
    }

    private static void requireToolArgumentContains(
            ScenarioExecution execution,
            String toolName,
            String argumentKey,
            String expectedFragment
    ) {
        ToolCall toolCall = requireToolCall(execution, toolName);
        String actualValue = toolCall.arguments().get(argumentKey);
        if (actualValue == null || !actualValue.contains(expectedFragment)) {
            throw new IllegalStateException(
                    "工具参数未包含预期片段: %s.%s".formatted(toolName, argumentKey)
            );
        }
    }

    private static ToolCall requireToolCall(ScenarioExecution execution, String toolName) {
        // assistant 消息里保存了模型原始发起的 tool_calls，
        // 这里按消息时间顺序平铺后，才能准确拿到真实请求参数。
        return execution.agentLoopResult().messages().stream()
                .flatMap(message -> message.toolCalls().stream())
                // 每个工具在当前最小场景里只应出现一次，
                // 因此找到第一个同名调用即可代表该步骤的真实参数。
                .filter(toolCall -> toolCall.toolName().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到工具调用: " + toolName));
    }

    private static void requireFinalAnswerContains(ScenarioExecution execution, String expectedText) {
        String finalAnswer = execution.agentLoopResult().finalAnswer();
        if (finalAnswer == null || !finalAnswer.contains(expectedText)) {
            throw new IllegalStateException("最终回答不符合预期: " + finalAnswer);
        }
    }

    private static String requireScenarioId(String value) {
        String safeValue = requireNonBlank(value, "id must not be blank.");
        if (!SCENARIO_ID_PATTERN.matcher(safeValue).matches()) {
            throw new IllegalArgumentException("id must match [a-z0-9][a-z0-9-]*.");
        }
        return safeValue;
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    private static CliModelConfig requireRealModelConfig(CliModelConfig modelConfig) {
        CliModelConfig safeModelConfig = Objects.requireNonNull(modelConfig, "modelConfig must not be null.");
        if (!"openai-compatible".equals(safeModelConfig.provider())
                && !"anthropic".equals(safeModelConfig.provider())) {
            throw new IllegalArgumentException(
                    "REAL_MODEL_PROVIDER 评估要求 REPOPILOT_MODEL_PROVIDER 为 openai-compatible 或 anthropic。"
            );
        }
        return safeModelConfig;
    }

    public enum RuntimeKind {
        SCRIPTED_RUNTIME,
        REAL_MODEL_PROVIDER
    }

    @FunctionalInterface
    public interface WorkspaceInitializer {

        void initialize(Path workspaceRoot) throws Exception;
    }

    @FunctionalInterface
    public interface ModelAdapterFactory {

        ModelAdapter create(Path workspaceRoot) throws Exception;
    }

    @FunctionalInterface
    public interface ScenarioVerifier {

        void verify(ScenarioExecution execution) throws Exception;
    }

    public record ScenarioExecution(
            Path workspaceRoot,
            AgentLoopResult agentLoopResult,
            List<TracePublisher.TraceEvent> traceEvents
    ) {

        public ScenarioExecution {
            workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null.");
            agentLoopResult = Objects.requireNonNull(agentLoopResult, "agentLoopResult must not be null.");
            traceEvents = List.copyOf(Objects.requireNonNull(traceEvents, "traceEvents must not be null."));
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
            if (cursor >= scriptedResponses.size()) {
                throw new IllegalStateException("脚本模型响应已耗尽。");
            }

            ModelResponse response = scriptedResponses.get(cursor);
            cursor += 1;
            return response;
        }
    }
}
