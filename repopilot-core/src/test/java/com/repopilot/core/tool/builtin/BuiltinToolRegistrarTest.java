package com.repopilot.core.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuiltinToolRegistrarTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void shouldRegisterBuiltinToolsInStableOrder() throws Exception {
        Files.writeString(workspaceRoot.resolve("README.md"), "RepoPilot");

        ToolRegistry toolRegistry = new ToolRegistry();
        BuiltinToolRegistrar.registerAll(toolRegistry, workspaceRoot);

        assertEquals(
                List.of("read_file", "grep_files", "run_command"),
                toolRegistry.list().stream().map(tool -> tool.name()).toList()
        );
        assertEquals(
                List.of("path"),
                toolRegistry.list().get(0).parametersSchema().get("required")
        );
        assertEquals(
                List.of("command"),
                toolRegistry.list().get(2).parametersSchema().get("required")
        );

        ToolExecutionResult result = toolRegistry.execute("read_file", Map.of("path", "README.md"));
        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
        assertEquals("RepoPilot", result.output());
    }
}
