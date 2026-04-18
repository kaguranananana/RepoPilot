package com.repopilot.core.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteFileToolTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void shouldWriteFileContentByRelativePath() throws Exception {
        ToolHandler tool = new WriteFileTool(workspaceRoot);

        ToolExecutionResult result = tool.execute(Map.of(
                "path", "docs/output.txt",
                "content", "第一行\n第二行\n"
        ));

        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
        assertEquals("第一行\n第二行\n", Files.readString(workspaceRoot.resolve("docs/output.txt")));
        assertEquals("已写入文件: docs/output.txt (2 行)", result.output());
    }

    @Test
    void shouldReturnRecoverableErrorWhenPathArgumentIsBlank() {
        ToolHandler tool = new WriteFileTool(workspaceRoot);

        ToolExecutionResult result = tool.execute(Map.of(
                "path", "   ",
                "content", "正文"
        ));

        assertEquals(ToolExecutionResult.Status.RECOVERABLE_ERROR, result.status());
        assertEquals("缺少必填参数: path", result.output());
    }

    @Test
    void shouldAllowWritingEmptyContent() throws Exception {
        ToolHandler tool = new WriteFileTool(workspaceRoot);

        ToolExecutionResult result = tool.execute(Map.of(
                "path", "docs/empty.txt",
                "content", ""
        ));

        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
        assertEquals("", Files.readString(workspaceRoot.resolve("docs/empty.txt")));
        assertEquals("已写入文件: docs/empty.txt (0 行)", result.output());
    }
}
