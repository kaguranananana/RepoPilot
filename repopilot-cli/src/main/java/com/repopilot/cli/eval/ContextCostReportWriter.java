package com.repopilot.cli.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repopilot.protocol.json.ProtocolObjectMapperFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * context-cost 报告写入器。
 * JSON 用于结构化对比，Markdown 用于简历复盘和人工阅读。
 */
public final class ContextCostReportWriter {

    private final ObjectMapper objectMapper;

    public ContextCostReportWriter() {
        this(ProtocolObjectMapperFactory.create());
    }

    ContextCostReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null.");
    }

    public void write(ContextCostEvalResult result, Path jsonOutput, Path markdownOutput) {
        Objects.requireNonNull(result, "result must not be null.");
        Path safeJsonOutput = normalizeOutput(jsonOutput);
        Path safeMarkdownOutput = normalizeOutput(markdownOutput);

        try {
            createParentDirectories(safeJsonOutput);
            createParentDirectories(safeMarkdownOutput);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(safeJsonOutput.toFile(), toJson(result));
            Files.writeString(safeMarkdownOutput, toMarkdown(result));
        } catch (IOException exception) {
            throw new IllegalStateException("写入 context-cost 报告失败: " + exception.getMessage(), exception);
        }
    }

    private ObjectNode toJson(ContextCostEvalResult result) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("measurementKind", result.measurementKind().name());
        root.put("generatedAt", result.generatedAt().toString());
        root.put("tokenEncoding", result.tokenEncoding());
        root.put("inputPricePerMillionTokens", result.inputPricePerMillionTokens());
        root.put("baselineStrategy", result.baselineStrategy());
        root.put("candidateStrategy", result.candidateStrategy());

        ContextCostEvalResult.Summary summary = result.summary();
        ObjectNode summaryNode = root.putObject("summary");
        summaryNode.put("scenarioCount", summary.scenarioCount());
        summaryNode.put("baselineTotalInputTokens", summary.baselineTotalInputTokens());
        summaryNode.put("candidateTotalInputTokens", summary.candidateTotalInputTokens());
        summaryNode.put("inputTokenReductionRate", summary.inputTokenReductionRate());
        summaryNode.put("baselinePeakInputTokens", summary.baselinePeakInputTokens());
        summaryNode.put("candidatePeakInputTokens", summary.candidatePeakInputTokens());
        summaryNode.put("peakInputTokenReductionRate", summary.peakInputTokenReductionRate());
        summaryNode.put("baselineInputCost", summary.baselineInputCost());
        summaryNode.put("candidateInputCost", summary.candidateInputCost());
        summaryNode.put("inputCostReductionRate", summary.inputCostReductionRate());

        ArrayNode scenarioNodes = root.putArray("scenarioComparisons");
        for (ContextCostEvalResult.ScenarioComparison comparison : result.scenarioComparisons()) {
            ObjectNode scenarioNode = scenarioNodes.addObject();
            scenarioNode.put("scenarioId", comparison.scenarioId());
            scenarioNode.put("title", comparison.title());
            scenarioNode.put("baselineInputTokens", comparison.baselineInputTokens());
            scenarioNode.put("candidateInputTokens", comparison.candidateInputTokens());
            scenarioNode.put("inputTokenReductionRate", comparison.inputTokenReductionRate());
            scenarioNode.put("baselinePeakInputTokens", comparison.baselinePeakInputTokens());
            scenarioNode.put("candidatePeakInputTokens", comparison.candidatePeakInputTokens());
            scenarioNode.put("peakInputTokenReductionRate", comparison.peakInputTokenReductionRate());
            scenarioNode.put("baselineModelCalls", comparison.baselineModelCalls());
            scenarioNode.put("candidateModelCalls", comparison.candidateModelCalls());
            scenarioNode.put("baselineCompactionCount", comparison.baselineCompactionCount());
            scenarioNode.put("candidateCompactionCount", comparison.candidateCompactionCount());
        }
        return root;
    }

    private String toMarkdown(ContextCostEvalResult result) {
        ContextCostEvalResult.Summary summary = result.summary();
        StringBuilder builder = new StringBuilder();
        builder.append("# Context Cost Eval Report").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("- measurementKind: ").append(result.measurementKind()).append(System.lineSeparator());
        builder.append("- tokenEncoding: ").append(result.tokenEncoding()).append(System.lineSeparator());
        builder.append("- baseline: ").append(result.baselineStrategy()).append(System.lineSeparator());
        builder.append("- candidate: ").append(result.candidateStrategy()).append(System.lineSeparator());
        builder.append("- 平均输入 token 降低：")
                .append(formatPercent(summary.inputTokenReductionRate()))
                .append(System.lineSeparator());
        builder.append("- 峰值输入 token 降低：")
                .append(formatPercent(summary.peakInputTokenReductionRate()))
                .append(System.lineSeparator()).append(System.lineSeparator());

        builder.append("| scenario | baseline input | candidate input | reduction | candidate compactions |")
                .append(System.lineSeparator());
        builder.append("| --- | ---: | ---: | ---: | ---: |").append(System.lineSeparator());
        for (ContextCostEvalResult.ScenarioComparison comparison : result.scenarioComparisons()) {
            builder.append("| ")
                    .append(comparison.scenarioId())
                    .append(" | ")
                    .append(comparison.baselineInputTokens())
                    .append(" | ")
                    .append(comparison.candidateInputTokens())
                    .append(" | ")
                    .append(formatPercent(comparison.inputTokenReductionRate()))
                    .append(" | ")
                    .append(comparison.candidateCompactionCount())
                    .append(" |")
                    .append(System.lineSeparator());
        }
        return builder.toString();
    }

    private Path normalizeOutput(Path output) {
        return Objects.requireNonNull(output, "output must not be null.").toAbsolutePath().normalize();
    }

    private void createParentDirectories(Path outputFile) throws IOException {
        Path parentDirectory = outputFile.getParent();
        if (parentDirectory != null) {
            Files.createDirectories(parentDirectory);
        }
    }

    private String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value * 100.0);
    }
}
