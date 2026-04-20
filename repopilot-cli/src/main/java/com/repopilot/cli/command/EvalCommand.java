package com.repopilot.cli.command;

import com.repopilot.cli.eval.EvalReportWriter;
import com.repopilot.cli.eval.EvalResult;
import com.repopilot.cli.eval.EvalRunner;
import com.repopilot.cli.eval.EvalScenario;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
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

    @Option(
            names = "--runtime-kind",
            defaultValue = "SCRIPTED_RUNTIME",
            description = "评估运行类型：SCRIPTED_RUNTIME 或 REAL_MODEL_PROVIDER。"
    )
    private EvalScenario.RuntimeKind runtimeKind;

    @Option(
            names = "--workspace-root",
            defaultValue = "target/repopilot-eval-workspaces",
            description = "评估场景工作区根目录。"
    )
    private Path workspaceRoot;

    @Option(
            names = "--output",
            defaultValue = "target/repopilot-eval-report.json",
            description = "结构化评估报告输出路径。"
    )
    private Path output;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        if (runtimeKind != EvalScenario.RuntimeKind.SCRIPTED_RUNTIME) {
            throw new IllegalArgumentException("真实模型 provider 评估场景尚未接入，不能复用 scripted runtime 指标。");
        }

        List<EvalScenario> scenarios = EvalScenario.defaultScriptedScenarios();
        EvalResult result = new EvalRunner(workspaceRoot, Clock.systemUTC()).run(scenarios);
        new EvalReportWriter().write(result, output);

        // CLI 只打印最小摘要，
        // 详细指标和逐场景失败诊断以 JSON 报告为准。
        spec.commandLine().getOut().printf(
                "eval report: %s%nscenario_count=%d task_success_rate=%.4f tool_call_valid_rate=%.4f%n",
                output.toAbsolutePath().normalize(),
                result.scenarioCount(),
                result.taskSuccessRate(),
                result.toolCallValidRate()
        );
        return result.taskSuccessRate() == 1.0 ? 0 : 1;
    }
}
