package com.repopilot.cli.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalRunnerTest {

    @TempDir
    Path tempRoot;

    @Test
    void shouldComputeCoreMetricsAndRecordFailureDiagnostics() throws Exception {
        Path workspaceRoot = tempRoot.resolve("eval-workspaces");
        EvalRunner runner = new EvalRunner(
                workspaceRoot,
                Clock.fixed(Instant.parse("2026-04-20T08:00:00Z"), ZoneOffset.UTC)
        );

        EvalResult result = runner.run(List.of(
                EvalScenario.scripted(
                        "read-file",
                        "读取文件",
                        "读取目标文件",
                        4,
                        workspace -> Files.writeString(workspace.resolve("note.txt"), "RepoPilot\n"),
                        List.of(
                                new ToolCallModelResponse(List.of(new ToolCall(
                                        "call-1",
                                        "read_file",
                                        Map.of("path", "note.txt")
                                ))),
                                new FinalModelResponse("读取完成")
                        ),
                        execution -> assertEquals("读取完成", execution.agentLoopResult().finalAnswer())
                ),
                EvalScenario.scripted(
                        "invalid-tool-call",
                        "工具缺参暴露",
                        "触发一次缺参工具调用",
                        4,
                        workspace -> {
                        },
                        List.of(
                                new ToolCallModelResponse(List.of(new ToolCall(
                                        "call-2",
                                        "read_file",
                                        Map.of()
                                ))),
                                new FinalModelResponse("缺参已暴露")
                        ),
                        execution -> assertTrue(execution.agentLoopResult().messages().stream()
                                .anyMatch(message -> message.content().contains("工具参数校验失败")))
                ),
                EvalScenario.scripted(
                        "failed-assertion",
                        "失败诊断",
                        "运行命令后触发验收失败",
                        4,
                        workspace -> Files.writeString(workspace.resolve("status.txt"), "status=draft\n"),
                        List.of(
                                new ToolCallModelResponse(List.of(new ToolCall(
                                        "call-3",
                                        "run_command",
                                        Map.of("command", "grep -n 'status=draft' status.txt")
                                ))),
                                new FinalModelResponse("命令完成")
                        ),
                        execution -> {
                            throw new IllegalStateException("验收失败: status 仍然是 draft");
                        }
                )
        ));

        assertEquals(EvalScenario.RuntimeKind.SCRIPTED_RUNTIME, result.runtimeKind());
        assertEquals(3, result.scenarioCount());
        assertEquals(3, result.toolCallCount());
        assertEquals(2, result.validToolCallCount());
        assertEquals(2.0 / 3.0, result.toolCallValidRate());
        assertEquals(2.0 / 3.0, result.taskSuccessRate());
        assertEquals(2.0, result.avgSteps());

        EvalResult.ScenarioResult failedScenario = result.scenarioResults().stream()
                .filter(scenarioResult -> scenarioResult.scenarioId().equals("failed-assertion"))
                .findFirst()
                .orElseThrow();
        assertEquals(false, failedScenario.success());
        assertEquals("assertion", failedScenario.failureStage());
        assertEquals("run_command", failedScenario.recentToolCall());
        assertTrue(failedScenario.finalError().contains("验收失败"));
        assertTrue(failedScenario.recentTraceRef().contains("TOOL_CALL_COMPLETED"));
    }
}
