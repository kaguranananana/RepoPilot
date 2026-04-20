package com.repopilot.cli.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repopilot.protocol.json.ProtocolObjectMapperFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 评估报告写入器。
 * 当前固定输出 JSON，
 * 让同一组任务在不同版本之间可以直接做结构化 diff。
 */
public final class EvalReportWriter {

    private final ObjectMapper objectMapper;

    public EvalReportWriter() {
        this(ProtocolObjectMapperFactory.create());
    }

    EvalReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null.");
    }

    public void write(EvalResult result, Path outputFile) {
        Objects.requireNonNull(result, "result must not be null.");
        Path safeOutputFile = Objects.requireNonNull(outputFile, "outputFile must not be null.")
                .toAbsolutePath()
                .normalize();

        try {
            Path parentDirectory = safeOutputFile.getParent();
            if (parentDirectory != null) {
                // 报告目录属于显式输出位置的一部分，
                // 写入前创建目录可以让 CLI 命令稳定落地指定文件。
                Files.createDirectories(parentDirectory);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(safeOutputFile.toFile(), toJson(result));
        } catch (IOException exception) {
            throw new IllegalStateException("写入评估报告失败: " + exception.getMessage(), exception);
        }
    }

    private ObjectNode toJson(EvalResult result) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("runtimeKind", result.runtimeKind().name());
        root.put("generatedAt", result.generatedAt().toString());
        root.put("scenarioCount", result.scenarioCount());

        ObjectNode metrics = root.putObject("metrics");
        metrics.put("toolCallValidRate", result.toolCallValidRate());
        metrics.put("taskSuccessRate", result.taskSuccessRate());
        metrics.put("avgSteps", result.avgSteps());
        metrics.put("avgDurationMillis", result.avgDurationMillis());
        metrics.put("toolCallCount", result.toolCallCount());
        metrics.put("validToolCallCount", result.validToolCallCount());

        ArrayNode scenarioResults = root.putArray("scenarioResults");
        for (EvalResult.ScenarioResult scenarioResult : result.scenarioResults()) {
            ObjectNode scenarioNode = scenarioResults.addObject();
            scenarioNode.put("scenarioId", scenarioResult.scenarioId());
            scenarioNode.put("title", scenarioResult.title());
            scenarioNode.put("runtimeKind", scenarioResult.runtimeKind().name());
            scenarioNode.put("success", scenarioResult.success());
            scenarioNode.put("steps", scenarioResult.steps());
            scenarioNode.put("durationMillis", scenarioResult.durationMillis());
            scenarioNode.put("toolCallCount", scenarioResult.toolCallCount());
            scenarioNode.put("validToolCallCount", scenarioResult.validToolCallCount());
            scenarioNode.put("failureStage", scenarioResult.failureStage());
            scenarioNode.put("recentToolCall", scenarioResult.recentToolCall());
            scenarioNode.put("finalError", scenarioResult.finalError());
            scenarioNode.put("recentTraceRef", scenarioResult.recentTraceRef());
        }
        return root;
    }
}
