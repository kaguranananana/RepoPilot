package com.repopilot.core.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrepFilesToolTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void shouldSearchMatchingLinesRecursively() throws Exception {
        Files.writeString(workspaceRoot.resolve("README.md"), "标题\nTODO 补文档\n");
        Path sourceFile = workspaceRoot.resolve("src/App.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "class App {\n    // TODO 补实现\n}\n");

        ToolHandler tool = new GrepFilesTool(workspaceRoot);
        ToolExecutionResult result = tool.execute(Map.of("pattern", "TODO"));

        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
        assertEquals(
                "README.md:2:TODO 补文档\nsrc/App.java:2:    // TODO 补实现",
                result.output()
        );
    }

    @Test
    void shouldReturnSuccessWhenNoMatchExists() throws Exception {
        Files.writeString(workspaceRoot.resolve("README.md"), "标题\n正文\n");

        ToolHandler tool = new GrepFilesTool(workspaceRoot);
        ToolExecutionResult result = tool.execute(Map.of("pattern", "TODO"));

        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
        assertEquals("未找到匹配内容: TODO", result.output());
    }

    @Test
    void shouldReturnRecoverableErrorWhenPatternIsInvalid() throws Exception {
        ToolHandler tool = new GrepFilesTool(workspaceRoot);

        ToolExecutionResult result = tool.execute(Map.of("pattern", "["));

        assertEquals(ToolExecutionResult.Status.RECOVERABLE_ERROR, result.status());
        assertEquals("无效的正则表达式: [", result.output());
    }
}
