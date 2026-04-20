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
        Path userHome = workspaceRoot.resolve("home");

        ToolRegistry toolRegistry = new ToolRegistry();
        BuiltinToolRegistrar.registerAll(
                toolRegistry,
                workspaceRoot,
                com.repopilot.core.skill.SkillLoader.createDefault(workspaceRoot, userHome)
        );

        assertEquals(
                List.of("read_file", "grep_files", "activate_skill", "apply_patch", "write_file", "run_command"),
                toolRegistry.list().stream().map(tool -> tool.name()).toList()
        );
        assertEquals(
                List.of("path"),
                toolRegistry.list().get(0).parametersSchema().get("required")
        );
        assertEquals(
                List.of("name"),
                toolRegistry.list().get(2).parametersSchema().get("required")
        );
        assertEquals(
                List.of("path", "patch"),
                toolRegistry.list().get(3).parametersSchema().get("required")
        );
        assertEquals(
                List.of("path", "content"),
                toolRegistry.list().get(4).parametersSchema().get("required")
        );
        assertEquals(
                List.of("command"),
                toolRegistry.list().get(5).parametersSchema().get("required")
        );

        ToolExecutionResult result = toolRegistry.execute("read_file", Map.of("path", "README.md"));
        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
        assertEquals("RepoPilot", result.output());

        ToolExecutionResult writeResult = toolRegistry.execute(
                "write_file",
                Map.of(
                        "path", "docs/generated.txt",
                        "content", "第一行\n第二行\n"
                )
        );
        assertEquals(ToolExecutionResult.Status.SUCCESS, writeResult.status());
        assertEquals("第一行\n第二行\n", Files.readString(workspaceRoot.resolve("docs/generated.txt")));

        ToolExecutionResult patchResult = toolRegistry.execute(
                "apply_patch",
                Map.of(
                        "path", "docs/generated.txt",
                        "patch", """
                                @@
                                 第一行
                                -第二行
                                +第二行-补丁修改
                                """
                )
        );
        assertEquals(ToolExecutionResult.Status.SUCCESS, patchResult.status());
        assertEquals("第一行\n第二行-补丁修改\n", Files.readString(workspaceRoot.resolve("docs/generated.txt")));
    }
}
