package com.repopilot.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.approval.ToolApprovalHandler;
import com.repopilot.core.permission.WorkspacePermissionPolicy;
import com.repopilot.core.review.DiffReviewService;
import com.repopilot.core.tool.ToolExecutionContext;
import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolRegistry;
import com.repopilot.core.tool.builtin.ApplyPatchTool;
import com.repopilot.core.tool.governance.GovernedToolExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanModeToolGovernanceTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void shouldDenyWriteToolInPlanModeBeforeApprovalOrExecution() throws Exception {
        Path targetFile = workspaceRoot.resolve("docs/plan.txt");
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, "status=draft\n");
        AtomicInteger approvalCount = new AtomicInteger();

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(
                "apply_patch",
                "应用补丁",
                Map.of("required", List.of("path", "patch")),
                new ApplyPatchTool(workspaceRoot)
        );
        GovernedToolExecutor governedToolExecutor = new GovernedToolExecutor(
                toolRegistry,
                new WorkspacePermissionPolicy(workspaceRoot),
                new DiffReviewService(workspaceRoot),
                request -> {
                    approvalCount.incrementAndGet();
                    return ToolApprovalHandler.ApprovalDecision.approve("测试批准");
                }
        );

        ToolExecutionResult result = governedToolExecutor.execute(
                new ToolExecutionContext(List.of(), AgentRunMode.PLAN),
                "apply_patch",
                Map.of(
                        "path", "docs/plan.txt",
                        "patch", """
                                @@
                                -status=draft
                                +status=ready
                                """
                )
        );

        assertEquals(ToolExecutionResult.Status.RECOVERABLE_ERROR, result.status());
        assertTrue(result.output().contains("PLAN"));
        assertTrue(result.output().contains("只读"));
        assertTrue(result.output().contains("apply_patch"));
        assertEquals(0, approvalCount.get());
        assertEquals("status=draft\n", Files.readString(targetFile));
    }

    @Test
    void shouldAllowReadOnlyToolInPlanMode() {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(
                "read_file",
                "读取文件",
                Map.of("required", List.of("path")),
                arguments -> ToolExecutionResult.success("文件内容")
        );
        GovernedToolExecutor governedToolExecutor = new GovernedToolExecutor(
                toolRegistry,
                new WorkspacePermissionPolicy(workspaceRoot),
                new DiffReviewService(workspaceRoot)
        );

        ToolExecutionResult result = governedToolExecutor.execute(
                new ToolExecutionContext(List.of(), AgentRunMode.PLAN),
                "read_file",
                Map.of("path", "docs/notes.txt")
        );

        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
        assertEquals("文件内容", result.output());
    }
}
