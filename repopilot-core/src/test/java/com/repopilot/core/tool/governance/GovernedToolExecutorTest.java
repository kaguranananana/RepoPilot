package com.repopilot.core.tool.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.approval.ToolApprovalHandler;
import com.repopilot.core.permission.PermissionPolicy;
import com.repopilot.core.permission.WorkspacePermissionPolicy;
import com.repopilot.core.review.DiffReviewService;
import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolRegistry;
import com.repopilot.core.tool.builtin.WriteFileTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GovernedToolExecutorTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void shouldRejectMissingRequiredArgumentBeforeExecutingHandler() {
        ToolRegistry toolRegistry = new ToolRegistry();
        AtomicInteger executionCount = new AtomicInteger();
        toolRegistry.register(
                "read_file",
                "读取文件",
                Map.of("required", List.of("path")),
                arguments -> {
                    executionCount.incrementAndGet();
                    return ToolExecutionResult.success("should-not-run");
                }
        );
        GovernedToolExecutor governedToolExecutor = new GovernedToolExecutor(
                toolRegistry,
                new WorkspacePermissionPolicy(workspaceRoot),
                new DiffReviewService(workspaceRoot)
        );

        ToolExecutionResult result = governedToolExecutor.execute("read_file", Map.of());

        assertEquals(ToolExecutionResult.Status.RECOVERABLE_ERROR, result.status());
        assertTrue(result.output().contains("缺少必填参数: path"));
        assertEquals(0, executionCount.get());
    }

    @Test
    void shouldReturnApprovalRequiredErrorWithDiffSummaryBeforeWrite() {
        ToolRegistry toolRegistry = new ToolRegistry();
        AtomicInteger executionCount = new AtomicInteger();
        toolRegistry.register(
                "write_file",
                "写入文件",
                Map.of("required", List.of("path", "content")),
                arguments -> {
                    executionCount.incrementAndGet();
                    return ToolExecutionResult.success("written");
                }
        );
        GovernedToolExecutor governedToolExecutor = new GovernedToolExecutor(
                toolRegistry,
                new WorkspacePermissionPolicy(workspaceRoot),
                new DiffReviewService(workspaceRoot)
        );

        ToolExecutionResult result = governedToolExecutor.execute(
                "write_file",
                Map.of(
                        "path", "docs/plan.txt",
                        "content", "新的内容\n"
                )
        );

        assertEquals(ToolExecutionResult.Status.RECOVERABLE_ERROR, result.status());
        assertTrue(result.output().contains("需要审批"));
        assertTrue(result.output().contains("docs/plan.txt"));
        assertTrue(result.output().contains("CREATE"));
        assertEquals(0, executionCount.get());
    }

    @Test
    void shouldConvertUnexpectedHandlerExceptionToFatalError() {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(
                "echo",
                "回显文本",
                Map.of("required", List.of("text")),
                arguments -> {
                    throw new IllegalStateException("boom");
                }
        );
        GovernedToolExecutor governedToolExecutor = new GovernedToolExecutor(
                toolRegistry,
                PermissionPolicy.allowAll(),
                new DiffReviewService(workspaceRoot)
        );

        ToolExecutionResult result = governedToolExecutor.execute(
                "echo",
                Map.of("text", "hello")
        );

        assertEquals(ToolExecutionResult.Status.FATAL_ERROR, result.status());
        assertTrue(result.output().contains("boom"));
    }

    @Test
    void shouldExecuteWriteFileAfterApprovalAccepted() throws Exception {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(
                "write_file",
                "写入文件",
                Map.of("required", List.of("path", "content")),
                new WriteFileTool(workspaceRoot)
        );
        GovernedToolExecutor governedToolExecutor = new GovernedToolExecutor(
                toolRegistry,
                new WorkspacePermissionPolicy(workspaceRoot),
                new DiffReviewService(workspaceRoot),
                request -> ToolApprovalHandler.ApprovalDecision.approve("用户已批准")
        );

        ToolExecutionResult result = governedToolExecutor.execute(
                "write_file",
                Map.of(
                        "path", "docs/approved.txt",
                        "content", "已批准写入\n"
                )
        );

        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
        assertEquals("已批准写入\n", Files.readString(workspaceRoot.resolve("docs/approved.txt")));
        assertTrue(result.output().contains("已写入文件: docs/approved.txt"));
    }
}
