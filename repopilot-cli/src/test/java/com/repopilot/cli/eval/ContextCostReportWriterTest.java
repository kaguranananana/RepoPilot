package com.repopilot.cli.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.protocol.json.ProtocolObjectMapperFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextCostReportWriterTest {

    @TempDir
    Path tempRoot;

    @Test
    void shouldWriteJsonAndMarkdownContextCostReports() throws Exception {
        ContextCostEvalResult result = new ContextCostEvalResult(
                ContextCostMeasurementKind.ESTIMATED_INPUT,
                Instant.parse("2026-04-21T02:00:00Z"),
                "cl100k_base",
                2.0,
                "NO_COMPACTION",
                "STRUCTURED_COMPACTION",
                new ContextCostEvalResult.Summary(
                        1,
                        10_000,
                        6_000,
                        0.4,
                        4_000,
                        2_500,
                        0.375,
                        0.02,
                        0.012,
                        0.4,
                        3,
                        3,
                        1.0
                ),
                List.of(new ContextCostEvalResult.ScenarioComparison(
                        "long-read",
                        "长文件读取",
                        10_000,
                        6_000,
                        0.4,
                        4_000,
                        2_500,
                        0.375,
                        6,
                        6,
                        0,
                        3,
                        0,
                        2,
                        0,
                        4,
                        3,
                        3,
                        1.0
                ))
        );
        Path jsonFile = tempRoot.resolve("context-cost.json");
        Path markdownFile = tempRoot.resolve("context-cost.md");

        new ContextCostReportWriter().write(result, jsonFile, markdownFile);

        JsonNode root = ProtocolObjectMapperFactory.create().readTree(Files.readString(jsonFile));
        assertEquals("ESTIMATED_INPUT", root.path("measurementKind").asText());
        assertEquals(0.4, root.path("summary").path("inputTokenReductionRate").asDouble());
        assertEquals("long-read", root.path("scenarioComparisons").get(0).path("scenarioId").asText());
        assertEquals(2, root.path("scenarioComparisons").get(0).path("candidateTokenBudgetCompactionCount").asInt());
        assertEquals(4, root.path("scenarioComparisons").get(0).path("candidateMicrocompactedToolResultCount").asInt());
        assertEquals(1.0, root.path("summary").path("candidateFactRetentionRate").asDouble());
        assertEquals(3, root.path("scenarioComparisons").get(0).path("candidateRetainedFactCount").asInt());

        String markdown = Files.readString(markdownFile);
        assertTrue(markdown.contains("Context Cost Eval Report"));
        assertTrue(markdown.contains("平均输入 token 降低：40.00%"));
        assertTrue(markdown.contains("关键事实保留率：100.00%"));
        assertTrue(markdown.contains("| long-read |"));
        assertTrue(markdown.contains("| long-read | 10000 | 6000 | 40.00% | 3 | 2 | 4 | 3/3 | 100.00% |"));
    }
}
