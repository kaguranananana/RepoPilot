package com.repopilot.cli.command;

import com.repopilot.cli.eval.ContextCostEvalResult;
import com.repopilot.cli.eval.ContextCostEvalRunner;
import com.repopilot.cli.eval.ContextCostMeasurementKind;
import com.repopilot.cli.eval.ContextCostReportWriter;
import com.repopilot.cli.eval.ContextCostScenario;
import com.repopilot.cli.eval.ContextCostScenarioFactory;
import com.repopilot.cli.runtime.CliModelConfig;
import com.repopilot.cli.runtime.JTokkitModelInputTokenEstimator;
import com.repopilot.cli.runtime.LocalEnvironmentMapLoader;
import com.repopilot.cli.runtime.ModelInputTokenEstimator;
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
 * `context-cost` 子命令运行上下文压缩 token 成本对比。
 */
@Command(
        name = "context-cost",
        mixinStandardHelpOptions = true,
        description = "对比完整历史回放和结构化上下文压缩的输入 token 成本。"
)
public class ContextCostCommand implements Callable<Integer> {

    private static final String DEFAULT_TOKEN_ENCODING = "cl100k_base";

    private final Clock clock;
    private final ScenarioFactory scenarioFactory;
    private final TokenEstimatorFactory tokenEstimatorFactory;

    @Option(
            names = "--measurement-kind",
            defaultValue = "ESTIMATED_INPUT",
            description = "计量口径：ESTIMATED_INPUT 或 REAL_USAGE。"
    )
    private ContextCostMeasurementKind measurementKind;

    @Option(
            names = "--workspace-root",
            description = "context-cost 场景工作区根目录；未指定时按计量口径选择默认目录。"
    )
    private Path workspaceRoot;

    @Option(
            names = "--json-output",
            description = "JSON 报告输出路径；未指定时按计量口径选择默认文件。"
    )
    private Path jsonOutput;

    @Option(
            names = "--markdown-output",
            description = "Markdown 报告输出路径；未指定时按计量口径选择默认文件。"
    )
    private Path markdownOutput;

    @Option(
            names = "--token-encoding",
            defaultValue = DEFAULT_TOKEN_ENCODING,
            description = "本地估算使用的 JTokkit encoding，例如 cl100k_base。"
    )
    private String tokenEncoding;

    @Option(
            names = "--input-price-per-million",
            defaultValue = "0.0",
            description = "每百万输入 token 价格；用于估算输入成本。"
    )
    private double inputPricePerMillionTokens;

    @Spec
    private CommandSpec spec;

    public ContextCostCommand() {
        this(
                Clock.systemUTC(),
                ContextCostCommand::loadDefaultScenarios,
                JTokkitModelInputTokenEstimator::new
        );
    }

    ContextCostCommand(
            Clock clock,
            ScenarioFactory scenarioFactory,
            TokenEstimatorFactory tokenEstimatorFactory
    ) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null.");
        this.scenarioFactory = Objects.requireNonNull(scenarioFactory, "scenarioFactory must not be null.");
        this.tokenEstimatorFactory =
                Objects.requireNonNull(tokenEstimatorFactory, "tokenEstimatorFactory must not be null.");
    }

    @Override
    public Integer call() {
        List<ContextCostScenario> scenarios = scenarioFactory.create(measurementKind);
        Path effectiveWorkspaceRoot = resolveWorkspaceRoot(measurementKind);
        Path effectiveJsonOutput = resolveJsonOutput(measurementKind);
        Path effectiveMarkdownOutput = resolveMarkdownOutput(measurementKind);
        ModelInputTokenEstimator tokenEstimator = tokenEstimatorFactory.create(tokenEncoding);

        ContextCostEvalResult result = new ContextCostEvalRunner(
                effectiveWorkspaceRoot,
                clock,
                tokenEstimator
        ).run(measurementKind, tokenEncoding, inputPricePerMillionTokens, scenarios);
        new ContextCostReportWriter().write(result, effectiveJsonOutput, effectiveMarkdownOutput);

        spec.commandLine().getOut().printf(
                "context cost report: %s%nmarkdown report: %s%nscenario_count=%d input_token_reduction_rate=%.4f peak_input_token_reduction_rate=%.4f%n",
                effectiveJsonOutput.toAbsolutePath().normalize(),
                effectiveMarkdownOutput.toAbsolutePath().normalize(),
                result.summary().scenarioCount(),
                result.summary().inputTokenReductionRate(),
                result.summary().peakInputTokenReductionRate()
        );
        return 0;
    }

    private Path resolveWorkspaceRoot(ContextCostMeasurementKind measurementKind) {
        if (workspaceRoot != null) {
            return workspaceRoot;
        }
        return switch (measurementKind) {
            case ESTIMATED_INPUT -> Path.of("target/repopilot-context-cost-estimated-workspaces");
            case REAL_USAGE -> Path.of("target/repopilot-context-cost-real-usage-workspaces");
        };
    }

    private Path resolveJsonOutput(ContextCostMeasurementKind measurementKind) {
        if (jsonOutput != null) {
            return jsonOutput;
        }
        return switch (measurementKind) {
            case ESTIMATED_INPUT -> Path.of("target/repopilot-context-cost-estimated-report.json");
            case REAL_USAGE -> Path.of("target/repopilot-context-cost-real-usage-report.json");
        };
    }

    private Path resolveMarkdownOutput(ContextCostMeasurementKind measurementKind) {
        if (markdownOutput != null) {
            return markdownOutput;
        }
        return switch (measurementKind) {
            case ESTIMATED_INPUT -> Path.of("target/repopilot-context-cost-estimated-report.md");
            case REAL_USAGE -> Path.of("target/repopilot-context-cost-real-usage-report.md");
        };
    }

    private static List<ContextCostScenario> loadDefaultScenarios(ContextCostMeasurementKind measurementKind) {
        return switch (measurementKind) {
            case ESTIMATED_INPUT -> ContextCostScenarioFactory.defaultEstimatedScenarios();
            case REAL_USAGE -> ContextCostScenarioFactory.defaultRealUsageScenarios(loadRealModelConfig());
        };
    }

    private static CliModelConfig loadRealModelConfig() {
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        return CliModelConfig.fromEnvironment(LocalEnvironmentMapLoader.load(projectRoot, System.getenv()));
    }

    @FunctionalInterface
    interface ScenarioFactory {

        List<ContextCostScenario> create(ContextCostMeasurementKind measurementKind);
    }

    @FunctionalInterface
    interface TokenEstimatorFactory {

        ModelInputTokenEstimator create(String tokenEncoding);
    }
}
