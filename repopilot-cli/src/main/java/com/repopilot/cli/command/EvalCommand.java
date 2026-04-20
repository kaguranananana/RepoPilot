package com.repopilot.cli.command;

import com.repopilot.cli.eval.EvalReportWriter;
import com.repopilot.cli.eval.EvalResult;
import com.repopilot.cli.eval.EvalRunner;
import com.repopilot.cli.eval.EvalScenario;
import com.repopilot.cli.runtime.CliModelConfig;
import com.repopilot.cli.runtime.LocalEnvironmentMapLoader;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * `eval` 子命令提供最小可重复评估入口。
 * 当前默认只运行 scripted runtime 场景，
 * 避免把确定性脚本模型的成功率误标成真实 provider 成功率。
 */
@Command(
        name = "eval",
        mixinStandardHelpOptions = true,
        description = "运行固定最小评估任务集并输出结构化报告。"
)
public class EvalCommand implements Callable<Integer> {

    private final Clock clock;
    private final ScenarioFactory scenarioFactory;

    @Option(
            names = "--runtime-kind",
            defaultValue = "SCRIPTED_RUNTIME",
            description = "评估运行类型：SCRIPTED_RUNTIME 或 REAL_MODEL_PROVIDER。"
    )
    private EvalScenario.RuntimeKind runtimeKind;

    @Option(
            names = "--workspace-root",
            description = "评估场景工作区根目录；未显式指定时会按 runtime-kind 选择默认目录。"
    )
    private Path workspaceRoot;

    @Option(
            names = "--output",
            description = "结构化评估报告输出路径；未显式指定时会按 runtime-kind 选择默认文件。"
    )
    private Path output;

    @Spec
    private CommandSpec spec;

    public EvalCommand() {
        this(Clock.systemUTC(), EvalCommand::loadDefaultScenarios);
    }

    EvalCommand(Clock clock, ScenarioFactory scenarioFactory) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null.");
        this.scenarioFactory = Objects.requireNonNull(scenarioFactory, "scenarioFactory must not be null.");
    }

    @Override
    public Integer call() {
        // 这里先按 runtime-kind 解析场景集，
        // 保证 scripted baseline 和真实模型评估各自走独立任务集，
        // 不会把两种口径的成功率混写到同一套默认场景里。
        List<EvalScenario> scenarios = scenarioFactory.create(runtimeKind);
        Path effectiveWorkspaceRoot = resolveWorkspaceRoot(runtimeKind);
        Path effectiveOutput = resolveOutput(runtimeKind);
        EvalResult result = new EvalRunner(effectiveWorkspaceRoot, clock).run(scenarios);
        new EvalReportWriter().write(result, effectiveOutput);

        // CLI 只打印最小摘要，
        // 详细指标和逐场景失败诊断以 JSON 报告为准。
        spec.commandLine().getOut().printf(
                "eval report: %s%nscenario_count=%d task_success_rate=%.4f tool_call_valid_rate=%.4f%n",
                effectiveOutput.toAbsolutePath().normalize(),
                result.scenarioCount(),
                result.taskSuccessRate(),
                result.toolCallValidRate()
        );
        return result.taskSuccessRate() == 1.0 ? 0 : 1;
    }

    private Path resolveWorkspaceRoot(EvalScenario.RuntimeKind runtimeKind) {
        if (workspaceRoot != null) {
            return workspaceRoot;
        }

        return switch (runtimeKind) {
            case SCRIPTED_RUNTIME -> Path.of("target/repopilot-eval-workspaces");
            case REAL_MODEL_PROVIDER -> Path.of("target/repopilot-real-model-eval-workspaces");
        };
    }

    private Path resolveOutput(EvalScenario.RuntimeKind runtimeKind) {
        if (output != null) {
            return output;
        }

        return switch (runtimeKind) {
            case SCRIPTED_RUNTIME -> Path.of("target/repopilot-eval-report.json");
            case REAL_MODEL_PROVIDER -> Path.of("target/repopilot-real-model-eval-report.json");
        };
    }

    private static List<EvalScenario> loadDefaultScenarios(EvalScenario.RuntimeKind runtimeKind) {
        return switch (runtimeKind) {
            case SCRIPTED_RUNTIME -> EvalScenario.defaultScriptedScenarios();
            case REAL_MODEL_PROVIDER -> EvalScenario.defaultRealModelScenarios(loadRealModelConfig());
        };
    }

    private static CliModelConfig loadRealModelConfig() {
        // 真实模型评估读取的是当前仓库根目录配置，
        // 而不是每个场景自己的临时工作区，
        // 这样 API Key / Base URL / Model 才不会随着场景重建被覆盖掉。
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        return CliModelConfig.fromEnvironment(LocalEnvironmentMapLoader.load(projectRoot, System.getenv()));
    }

    @FunctionalInterface
    interface ScenarioFactory {

        List<EvalScenario> create(EvalScenario.RuntimeKind runtimeKind);
    }
}
