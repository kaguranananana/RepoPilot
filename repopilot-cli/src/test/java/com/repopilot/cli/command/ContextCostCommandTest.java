package com.repopilot.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.cli.eval.ContextCostMeasurementKind;
import com.repopilot.cli.eval.ContextCostScenario;
import com.repopilot.cli.eval.ContextCostStrategy;
import com.repopilot.core.context.ContextCompactionPolicy;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
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

class ContextCostCommandTest {

    @TempDir
    Path tempRoot;

    @Test
    void shouldRunContextCostCommandAndWriteReports() throws Exception {
        Path workspaceRoot = tempRoot.resolve("workspaces");
        Path jsonOutput = tempRoot.resolve("context-cost.json");
        Path markdownOutput = tempRoot.resolve("context-cost.md");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CommandLine commandLine = new CommandLine(new ContextCostCommand(
                Clock.fixed(Instant.parse("2026-04-21T03:00:00Z"), ZoneOffset.UTC),
                measurementKind -> {
                    assertEquals(ContextCostMeasurementKind.ESTIMATED_INPUT, measurementKind);
                    return List.of(longReadScenario());
                },
                encodingName -> (messages, tools) -> messages.stream()
                        .mapToInt(message -> message.content().length())
                        .sum()
        ));
        commandLine.setOut(new PrintWriter(outputStream, true, StandardCharsets.UTF_8));

        int exitCode = commandLine.execute(
                "--workspace-root", workspaceRoot.toString(),
                "--json-output", jsonOutput.toString(),
                "--markdown-output", markdownOutput.toString(),
                "--token-encoding", "test-estimator"
        );

        assertEquals(0, exitCode);
        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("input_token_reduction_rate="));
        assertTrue(Files.isRegularFile(jsonOutput));
        assertTrue(Files.isRegularFile(markdownOutput));
        JsonNode report = ProtocolObjectMapperFactory.create().readTree(Files.readString(jsonOutput));
        assertEquals("ESTIMATED_INPUT", report.path("measurementKind").asText());
        assertTrue(report.path("summary").path("inputTokenReductionRate").asDouble() > 0.0);
    }

    private ContextCostScenario longReadScenario() {
        return new ContextCostScenario(
                "long-read",
                "长文件读取",
                "连续读取文件。",
                8,
                new ContextCompactionPolicy(10_000, 9_999, 10),
                new ContextCompactionPolicy(4, 1, 1),
                workspace -> {
                    Files.createDirectories(workspace.resolve("notes"));
                    Files.writeString(workspace.resolve("notes/a.txt"), "A".repeat(2_000));
                    Files.writeString(workspace.resolve("notes/b.txt"), "B".repeat(2_000));
                    Files.writeString(workspace.resolve("notes/c.txt"), "C".repeat(2_000));
                    Files.writeString(workspace.resolve("notes/d.txt"), "D".repeat(2_000));
                },
                (workspace, strategy) -> scriptedModel(List.of(
                        tool("call-a", "read_file", Map.of("path", "notes/a.txt")),
                        tool("call-b", "read_file", Map.of("path", "notes/b.txt")),
                        tool("call-c", "read_file", Map.of("path", "notes/c.txt")),
                        tool("call-d", "read_file", Map.of("path", "notes/d.txt")),
                        new FinalModelResponse("完成")
                )),
                execution -> assertTrue(execution.agentLoopResult().finalAnswer().contains("完成"))
        );
    }

    private static ToolCallModelResponse tool(String id, String toolName, Map<String, String> arguments) {
        return new ToolCallModelResponse(List.of(new ToolCall(id, toolName, arguments)));
    }

    private static ModelAdapter scriptedModel(List<ModelResponse> responses) {
        return new ScriptedModelAdapter(responses);
    }

    private static final class ScriptedModelAdapter implements ModelAdapter {

        private final List<ModelResponse> responses;
        private int cursor;

        private ScriptedModelAdapter(List<ModelResponse> responses) {
            this.responses = responses;
        }

        @Override
        public ModelResponse next(List<com.repopilot.core.model.ConversationMessage> messages) {
            ModelResponse response = responses.get(cursor);
            cursor += 1;
            return response;
        }
    }
}
