package com.repopilot.core.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadFileToolTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void shouldReadFileContentByRelativePath() throws Exception {
        Path targetFile = workspaceRoot.resolve("docs/notes.txt");
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, "第一行\n第二行\n");

        ToolHandler tool = new ReadFileTool(workspaceRoot);
        ToolExecutionResult result = tool.execute(Map.of("path", "docs/notes.txt"));

        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
        assertEquals("第一行\n第二行\n", result.output());
    }

    @Test
    void shouldReturnRecoverableErrorWhenFileDoesNotExist() throws Exception {
        ToolHandler tool = new ReadFileTool(workspaceRoot);

        ToolExecutionResult result = tool.execute(Map.of("path", "missing.txt"));

        assertEquals(ToolExecutionResult.Status.RECOVERABLE_ERROR, result.status());
        assertEquals("文件不存在: missing.txt", result.output());
    }

    @Test
    void shouldReturnRecoverableErrorWhenPathArgumentIsBlank() throws Exception {
        ToolHandler tool = new ReadFileTool(workspaceRoot);

        ToolExecutionResult result = tool.execute(Map.of("path", "   "));

        assertEquals(ToolExecutionResult.Status.RECOVERABLE_ERROR, result.status());
        assertEquals("缺少必填参数: path", result.output());
    }
}
