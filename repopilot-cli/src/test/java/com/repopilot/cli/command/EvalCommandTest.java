package com.repopilot.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.cli.eval.EvalScenario;
import com.repopilot.core.context.ContextCompactionPolicy;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.protocol.json.ProtocolObjectMapperFactory;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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

    @Test
    void shouldRunInjectedRealModelScenariosAndWriteReport() throws Exception {
        Path workspaceRoot = tempRoot.resolve("real-workspaces");
        Path outputFile = tempRoot.resolve("real-report.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CommandLine commandLine = new CommandLine(new EvalCommand(
                Clock.fixed(Instant.parse("2026-04-20T09:00:00Z"), ZoneOffset.UTC),
                runtimeKind -> {
                    assertEquals(EvalScenario.RuntimeKind.REAL_MODEL_PROVIDER, runtimeKind);
                    return List.of(new EvalScenario(
                            "file-read",
                            "真实模型读取",
                            EvalScenario.RuntimeKind.REAL_MODEL_PROVIDER,
                            "读取 README.md",
                            4,
                            ContextCompactionPolicy.defaultPolicy(),
                            workspace -> Files.writeString(workspace.resolve("README.md"), "# RepoPilot\n"),
                            workspace -> new ScriptedModelAdapter(List.of(
                                    new ToolCallModelResponse(List.of(new ToolCall(
                                            "call-1",
                                            "read_file",
                                            Map.of("path", "README.md")
                                    ))),
                                    new FinalModelResponse("读取完成")
                            )),
                            execution -> assertTrue(execution.agentLoopResult().messages().stream()
                                    .anyMatch(message -> message.content().contains("# RepoPilot")))
                    ));
                }
        ));
        commandLine.setOut(new PrintWriter(outputStream, true, StandardCharsets.UTF_8));

        int exitCode = commandLine.execute(
                "--runtime-kind", "REAL_MODEL_PROVIDER",
                "--workspace-root", workspaceRoot.toString(),
                "--output", outputFile.toString()
        );

        assertEquals(0, exitCode);
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("task_success_rate=1.0000"));
        JsonNode report = ProtocolObjectMapperFactory.create().readTree(Files.readString(outputFile));
        assertEquals("REAL_MODEL_PROVIDER", report.path("runtimeKind").asText());
        assertEquals(1, report.path("scenarioCount").asInt());
        assertEquals(1.0, report.path("metrics").path("taskSuccessRate").asDouble());
    }

    private static final class ScriptedModelAdapter implements ModelAdapter {

        private final List<ModelResponse> scriptedResponses;
        private int cursor;

        private ScriptedModelAdapter(List<ModelResponse> scriptedResponses) {
            this.scriptedResponses = scriptedResponses;
        }

        @Override
        public ModelResponse next(List<com.repopilot.core.model.ConversationMessage> messages) {
            ModelResponse response = scriptedResponses.get(cursor);
            cursor += 1;
            return response;
        }
    }
}
