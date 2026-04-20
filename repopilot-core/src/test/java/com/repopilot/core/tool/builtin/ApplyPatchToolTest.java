package com.repopilot.core.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplyPatchToolTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void shouldApplyPatchAndReturnSummary() throws Exception {
        Path targetFile = workspaceRoot.resolve("docs/notes.txt");
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, "第一行\n第二行\n");
        ToolHandler tool = new ApplyPatchTool(workspaceRoot);

        ToolExecutionResult result = tool.execute(Map.of(
                "path", "docs/notes.txt",
                "patch", """
                        @@
                         第一行
                        -第二行
                        +第二行-已更新
                        """
        ));

        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
        assertEquals("第一行\n第二行-已更新\n", Files.readString(targetFile));
        assertTrue(result.output().contains("PATCH_APPLY"));
        assertTrue(result.output().contains("path: docs/notes.txt"));
        assertTrue(result.output().contains("addedLineCount: 1"));
        assertTrue(result.output().contains("removedLineCount: 1"));
    }

    @Test
    void shouldReturnRecoverableErrorWhenPatchContextDoesNotMatch() throws Exception {
        Path targetFile = workspaceRoot.resolve("docs/notes.txt");
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, "第一行\n第二行\n");
        ToolHandler tool = new ApplyPatchTool(workspaceRoot);

        ToolExecutionResult result = tool.execute(Map.of(
                "path", "docs/notes.txt",
                "patch", """
                        @@
                        -不存在
                        +替换内容
                        """
        ));

        assertEquals(ToolExecutionResult.Status.RECOVERABLE_ERROR, result.status());
        assertTrue(result.output().contains("CONTEXT_MISMATCH"));
        assertEquals("第一行\n第二行\n", Files.readString(targetFile));
    }

    @Test
    void shouldPreserveSemanticTrailingWhitespaceInsidePatch() throws Exception {
        Path targetFile = workspaceRoot.resolve("docs/notes.txt");
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, "value\n");
        ToolHandler tool = new ApplyPatchTool(workspaceRoot);

        ToolExecutionResult result = tool.execute(Map.of(
                "path", "docs/notes.txt",
                "patch", "@@\n-value\n+value  "
        ));

        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
        assertEquals("value  \n", Files.readString(targetFile));
    }
}
