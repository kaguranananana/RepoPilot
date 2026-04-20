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

class EvalReportWriterTest {

    @TempDir
    Path tempRoot;

    @Test
    void shouldWriteStructuredJsonReportForVersionComparison() throws Exception {
        EvalResult result = new EvalResult(
                EvalScenario.RuntimeKind.SCRIPTED_RUNTIME,
                Instant.parse("2026-04-20T08:00:00Z"),
                1,
                1,
                1,
                1.0,
                1.0,
                2.0,
                0.0,
                List.of(new EvalResult.ScenarioResult(
                        "read-file",
                        "读取文件",
                        EvalScenario.RuntimeKind.SCRIPTED_RUNTIME,
                        true,
                        2,
                        0,
                        1,
                        1,
                        "",
                        "read_file",
                        "",
                        "TOOL_CALL_COMPLETED#step=1#tool=read_file"
                ))
        );
        Path outputFile = tempRoot.resolve("report.json");

        new EvalReportWriter().write(result, outputFile);

        JsonNode root = ProtocolObjectMapperFactory.create().readTree(Files.readString(outputFile));
        assertEquals("SCRIPTED_RUNTIME", root.path("runtimeKind").asText());
        assertEquals(1.0, root.path("metrics").path("taskSuccessRate").asDouble());
        assertEquals(1.0, root.path("metrics").path("toolCallValidRate").asDouble());
        assertEquals("read-file", root.path("scenarioResults").get(0).path("scenarioId").asText());
        assertTrue(Files.isRegularFile(outputFile));
    }
}
