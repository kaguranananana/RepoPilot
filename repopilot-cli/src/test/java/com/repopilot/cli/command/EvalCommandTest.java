package com.repopilot.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.protocol.json.ProtocolObjectMapperFactory;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class EvalCommandTest {

    @TempDir
    Path tempRoot;

    @Test
    void shouldRunDefaultScriptedScenariosAndWriteReport() throws Exception {
        Path workspaceRoot = tempRoot.resolve("workspaces");
        Path outputFile = tempRoot.resolve("report.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CommandLine commandLine = new CommandLine(new EvalCommand());
        commandLine.setOut(new PrintWriter(outputStream, true, StandardCharsets.UTF_8));

        int exitCode = commandLine.execute(
                "--workspace-root", workspaceRoot.toString(),
                "--output", outputFile.toString()
        );

        assertEquals(0, exitCode);
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("task_success_rate=1.0000"));
        JsonNode report = ProtocolObjectMapperFactory.create().readTree(Files.readString(outputFile));
        assertEquals("SCRIPTED_RUNTIME", report.path("runtimeKind").asText());
        assertEquals(10, report.path("scenarioCount").asInt());
        assertEquals(1.0, report.path("metrics").path("taskSuccessRate").asDouble());
        assertTrue(Files.isDirectory(workspaceRoot.resolve("context-compaction")));
    }
}
