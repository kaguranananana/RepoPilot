package com.repopilot.core.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunCommandToolTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void shouldExecuteCommandInsideWorkspace() throws Exception {
        Files.writeString(workspaceRoot.resolve("greeting.txt"), "hello");

        ToolHandler tool = new RunCommandTool(workspaceRoot);
        ToolExecutionResult result = tool.execute(Map.of("command", "cat greeting.txt"));

        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
        assertEquals("exitCode: 0\nstdout:\nhello\nstderr:\n", result.output());
    }

    @Test
    void shouldReturnRecoverableErrorWhenCommandExitsNonZero() throws Exception {
        ToolHandler tool = new RunCommandTool(workspaceRoot);

        ToolExecutionResult result = tool.execute(Map.of("command", "printf 'boom' >&2; exit 7"));

        assertEquals(ToolExecutionResult.Status.RECOVERABLE_ERROR, result.status());
        assertEquals("exitCode: 7\nstdout:\n\nstderr:\nboom", result.output());
    }

    @Test
    void shouldReturnRecoverableErrorWhenCommandArgumentIsBlank() throws Exception {
        ToolHandler tool = new RunCommandTool(workspaceRoot);

        ToolExecutionResult result = tool.execute(Map.of("command", "  "));

        assertEquals(ToolExecutionResult.Status.RECOVERABLE_ERROR, result.status());
        assertEquals("缺少必填参数: command", result.output());
    }
}
