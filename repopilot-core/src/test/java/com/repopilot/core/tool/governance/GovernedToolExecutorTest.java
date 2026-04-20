package com.repopilot.core.tool.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.approval.ToolApprovalHandler;
import com.repopilot.core.permission.PermissionPolicy;
import com.repopilot.core.permission.WorkspacePermissionPolicy;
import com.repopilot.core.review.DiffReviewService;
import com.repopilot.core.tool.ToolExecutionContext;
import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolRegistry;
import com.repopilot.core.tool.builtin.ApplyPatchTool;
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

    @Test
    void shouldReturnApprovalRequiredErrorWithDiffSummaryBeforeApplyPatch() throws Exception {
        Path targetFile = workspaceRoot.resolve("docs/plan.txt");
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, "状态: draft\n");
        ToolRegistry toolRegistry = new ToolRegistry();
        AtomicInteger executionCount = new AtomicInteger();
        toolRegistry.register(
                "apply_patch",
                "应用补丁",
                Map.of("required", List.of("path", "patch")),
                arguments -> {
                    executionCount.incrementAndGet();
                    return ToolExecutionResult.success("patched");
                }
        );
        GovernedToolExecutor governedToolExecutor = new GovernedToolExecutor(
                toolRegistry,
                new WorkspacePermissionPolicy(workspaceRoot),
                new DiffReviewService(workspaceRoot)
        );

        ToolExecutionResult result = governedToolExecutor.execute(
                "apply_patch",
                Map.of(
                        "path", "docs/plan.txt",
                        "patch", """
                                @@
                                -状态: draft
                                +状态: ready
                                """
                )
        );

        assertEquals(ToolExecutionResult.Status.RECOVERABLE_ERROR, result.status());
        assertTrue(result.output().contains("需要审批"));
        assertTrue(result.output().contains("docs/plan.txt"));
        assertTrue(result.output().contains("DIFF_REVIEW"));
        assertTrue(result.output().contains("addedLineCount: 1"));
        assertTrue(result.output().contains("removedLineCount: 1"));
        assertEquals(0, executionCount.get());
    }

    @Test
    void shouldExecuteApplyPatchAfterApprovalAccepted() throws Exception {
        Path targetFile = workspaceRoot.resolve("docs/approved.txt");
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, "审批: pending\n");
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
                request -> ToolApprovalHandler.ApprovalDecision.approve("用户已批准")
        );

        ToolExecutionResult result = governedToolExecutor.execute(
                "apply_patch",
                Map.of(
                        "path", "docs/approved.txt",
                        "patch", """
                                @@
                                -审批: pending
                                +审批: approved
                                """
                )
        );

        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
        assertEquals("审批: approved\n", Files.readString(targetFile));
        assertTrue(result.output().contains("PATCH_APPLY"));
    }

    @Test
    void shouldRejectToolOutsideActivatedSkillAllowedTools() {
        ToolRegistry toolRegistry = new ToolRegistry();
        AtomicInteger executionCount = new AtomicInteger();
        toolRegistry.register(
                "read_file",
                "读取文件",
                Map.of("required", List.of("path")),
                arguments -> ToolExecutionResult.success("read")
        );
        toolRegistry.register(
                "grep_files",
                "搜索文件",
                Map.of("required", List.of("pattern")),
                arguments -> ToolExecutionResult.success("grep")
        );
        toolRegistry.register(
                "run_command",
                "执行命令",
                Map.of("required", List.of("command")),
                arguments -> {
                    executionCount.incrementAndGet();
                    return ToolExecutionResult.success("should-not-run");
                }
        );
        GovernedToolExecutor governedToolExecutor = new GovernedToolExecutor(
                toolRegistry,
                PermissionPolicy.allowAll(),
                new DiffReviewService(workspaceRoot)
        );

        ToolExecutionResult result = governedToolExecutor.execute(
                new ToolExecutionContext(List.of(new ConversationMessage(
                        MessageRole.SYSTEM,
                        """
                                # Activated Skill
                                name: readonly
                                source: project
                                allowed-tools: read_file, grep_files

                                ## Readonly Skill
                                只读分析。
                                """.strip()
                ))),
                "run_command",
                Map.of("command", "echo hi")
        );

        assertEquals(ToolExecutionResult.Status.RECOVERABLE_ERROR, result.status());
        assertTrue(result.output().contains("当前激活 Skill 不允许工具: run_command"));
        assertTrue(result.output().contains("read_file"));
        assertTrue(result.output().contains("grep_files"));
        assertEquals(0, executionCount.get());
    }
}
