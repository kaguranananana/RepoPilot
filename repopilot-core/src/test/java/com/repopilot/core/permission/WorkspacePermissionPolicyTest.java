package com.repopilot.core.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.tool.ToolDefinition;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacePermissionPolicyTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void shouldAllowReadOnlyToolInsideWorkspace() {
        PermissionPolicy permissionPolicy = new WorkspacePermissionPolicy(workspaceRoot);

        PermissionPolicy.PermissionDecision decision = permissionPolicy.evaluate(
                new ToolDefinition(
                        "read_file",
                        "读取文件",
                        Map.of("required", List.of("path"))
                ),
                Map.of("path", "docs/notes.txt")
        );

        assertEquals(PermissionPolicy.PermissionDisposition.ALLOW, decision.disposition());
        assertTrue(decision.reason().contains("工作区"));
    }

    @Test
    void shouldDenyOutsideWorkspaceBeforeAskingForWriteApproval() {
        PermissionPolicy permissionPolicy = new WorkspacePermissionPolicy(workspaceRoot);

        PermissionPolicy.PermissionDecision decision = permissionPolicy.evaluate(
                new ToolDefinition(
                        "write_file",
                        "写入文件",
                        Map.of("required", List.of("path", "content"))
                ),
                Map.of(
                        "path", "../secret.txt",
                        "content", "top-secret"
                )
        );

        assertEquals(PermissionPolicy.PermissionDisposition.DENY, decision.disposition());
        assertTrue(decision.reason().contains("工作区外"));
    }

    @Test
    void shouldAskBeforeAllowingRunCommand() {
        PermissionPolicy permissionPolicy = new WorkspacePermissionPolicy(workspaceRoot);

        PermissionPolicy.PermissionDecision decision = permissionPolicy.evaluate(
                new ToolDefinition(
                        "run_command",
                        "执行命令",
                        Map.of("required", List.of("command"))
                ),
                Map.of("command", "mvn test")
        );

        assertEquals(PermissionPolicy.PermissionDisposition.ASK, decision.disposition());
        assertTrue(decision.reason().contains("审批"));
    }

    @Test
    void shouldDenyUnknownToolByDefault() {
        PermissionPolicy permissionPolicy = new WorkspacePermissionPolicy(workspaceRoot);

        PermissionPolicy.PermissionDecision decision = permissionPolicy.evaluate(
                new ToolDefinition(
                        "echo",
                        "回显文本",
                        Map.of("required", List.of("text"))
                ),
                Map.of("text", "hello")
        );

        assertEquals(PermissionPolicy.PermissionDisposition.DENY, decision.disposition());
        assertTrue(decision.reason().contains("fail-closed"));
    }
}
