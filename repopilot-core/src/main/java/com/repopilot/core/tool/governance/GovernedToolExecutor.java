package com.repopilot.core.tool.governance;

import com.repopilot.core.approval.ToolApprovalHandler;
import com.repopilot.core.approval.ToolApprovalHandler.ApprovalDecision;
import com.repopilot.core.approval.ToolApprovalHandler.ApprovalRequest;
import com.repopilot.core.permission.PermissionPolicy;
import com.repopilot.core.permission.PermissionPolicy.PermissionDecision;
import com.repopilot.core.permission.PermissionPolicy.PermissionDisposition;
import com.repopilot.core.review.DiffReviewService;
import com.repopilot.core.review.DiffReviewService.DiffReviewSummary;
import com.repopilot.core.tool.ToolDefinition;
import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolNotFoundException;
import com.repopilot.core.tool.ToolRegistry;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 受治理的工具执行器。
 * 它把“查找工具 -> 校验 -> 权限 -> 执行 -> 结构化结果”收敛成一条统一流水线，
 * 避免 AgentLoop 同时消费多套异常语义。
 */
public final class GovernedToolExecutor {

    private final ToolRegistry toolRegistry;
    private final PermissionPolicy permissionPolicy;
    private final DiffReviewService diffReviewService;
    private final ToolApprovalHandler toolApprovalHandler;
    private final List<PreExecutionHook> preExecutionHooks;
    private final List<PostExecutionHook> postExecutionHooks;

    public GovernedToolExecutor(
            ToolRegistry toolRegistry,
            PermissionPolicy permissionPolicy,
            DiffReviewService diffReviewService
    ) {
        this(toolRegistry, permissionPolicy, diffReviewService, ToolApprovalHandler.denyAll(), List.of(), List.of());
    }

    public GovernedToolExecutor(
            ToolRegistry toolRegistry,
            PermissionPolicy permissionPolicy,
            DiffReviewService diffReviewService,
            ToolApprovalHandler toolApprovalHandler
    ) {
        this(toolRegistry, permissionPolicy, diffReviewService, toolApprovalHandler, List.of(), List.of());
    }

    public GovernedToolExecutor(
            ToolRegistry toolRegistry,
            PermissionPolicy permissionPolicy,
            DiffReviewService diffReviewService,
            List<PreExecutionHook> preExecutionHooks,
            List<PostExecutionHook> postExecutionHooks
    ) {
        this(toolRegistry, permissionPolicy, diffReviewService, ToolApprovalHandler.denyAll(), preExecutionHooks, postExecutionHooks);
    }

    public GovernedToolExecutor(
            ToolRegistry toolRegistry,
            PermissionPolicy permissionPolicy,
            DiffReviewService diffReviewService,
            ToolApprovalHandler toolApprovalHandler,
            List<PreExecutionHook> preExecutionHooks,
            List<PostExecutionHook> postExecutionHooks
    ) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null.");
        this.permissionPolicy = Objects.requireNonNull(permissionPolicy, "permissionPolicy must not be null.");
        this.diffReviewService = Objects.requireNonNull(diffReviewService, "diffReviewService must not be null.");
        this.toolApprovalHandler = Objects.requireNonNull(toolApprovalHandler, "toolApprovalHandler must not be null.");
        this.preExecutionHooks = List.copyOf(preExecutionHooks);
        this.postExecutionHooks = List.copyOf(postExecutionHooks);
    }

    public ToolExecutionResult execute(String toolName, Map<String, String> arguments) {
        String safeToolName = requireNonBlank(toolName, "toolName must not be blank.");
        Map<String, String> safeArguments = arguments == null ? Map.of() : Map.copyOf(arguments);

        ToolDefinition toolDefinition;
        try {
            toolDefinition = toolRegistry.requireDefinition(safeToolName);
        } catch (ToolNotFoundException exception) {
            return ToolExecutionResult.recoverableError("工具不存在: " + safeToolName);
        }

        // 先按 schema 的 required 字段做最小刚性校验，
        // 这样缺参错误会在真正执行前被统一收敛成治理层输出，
        // 不再散落到具体 handler 里各写各的错误协议。
        ToolExecutionResult schemaValidationFailure = validateRequiredArguments(toolDefinition, safeArguments);
        if (schemaValidationFailure != null) {
            return schemaValidationFailure;
        }

        // pre-execution hook 先于权限判断执行，
        // 为后续补业务级校验、审计埋点或预算检查预留稳定扩展点。
        ToolExecutionResult preHookFailure = runPreExecutionHooks(toolDefinition, safeArguments);
        if (preHookFailure != null) {
            return preHookFailure;
        }

        PermissionDecision permissionDecision;
        try {
            permissionDecision = permissionPolicy.evaluate(toolDefinition, safeArguments);
        } catch (RuntimeException exception) {
            return ToolExecutionResult.fatalError("权限策略执行失败: " + exception.getMessage());
        }

        // 权限拒绝和待审批都统一收敛成结构化工具错误，
        // 这样主循环仍然只消费 SUCCESS / RECOVERABLE_ERROR / FATAL_ERROR 三态。
        if (permissionDecision.disposition() == PermissionDisposition.DENY) {
            return ToolExecutionResult.recoverableError("权限拒绝: " + permissionDecision.reason());
        }
        if (permissionDecision.disposition() == PermissionDisposition.ASK) {
            return executeAfterApproval(toolDefinition, safeArguments, permissionDecision);
        }

        ToolExecutionResult executionResult = executeRegisteredTool(safeToolName, safeArguments);
        if (executionResult == null) {
            return ToolExecutionResult.fatalError("工具返回了空结果: " + safeToolName);
        }

        ToolExecutionResult postHookFailure = runPostExecutionHooks(toolDefinition, safeArguments, executionResult);
        if (postHookFailure != null) {
            return postHookFailure;
        }

        return executionResult;
    }

    private ToolExecutionResult executeRegisteredTool(String toolName, Map<String, String> arguments) {
        try {
            return toolRegistry.execute(toolName, arguments);
        } catch (RuntimeException exception) {
            return ToolExecutionResult.fatalError("工具执行异常: " + exception.getMessage());
        }
    }

    private ToolExecutionResult validateRequiredArguments(ToolDefinition toolDefinition, Map<String, String> arguments) {
        Object requiredObject = toolDefinition.parametersSchema().get("required");
        if (requiredObject == null) {
            return null;
        }
        if (!(requiredObject instanceof List<?> requiredArguments)) {
            return ToolExecutionResult.fatalError("工具 schema 非法: required 必须是字符串列表");
        }

        for (Object requiredArgument : requiredArguments) {
            if (!(requiredArgument instanceof String key)) {
                return ToolExecutionResult.fatalError("工具 schema 非法: required 元素必须是字符串");
            }

            String value = arguments.get(key);
            if (value == null || value.isBlank()) {
                return ToolExecutionResult.recoverableError("工具参数校验失败: 缺少必填参数: " + key);
            }
        }
        return null;
    }

    private ToolExecutionResult runPreExecutionHooks(ToolDefinition toolDefinition, Map<String, String> arguments) {
        try {
            for (PreExecutionHook hook : preExecutionHooks) {
                hook.beforeExecution(toolDefinition, arguments);
            }
            return null;
        } catch (RuntimeException exception) {
            return ToolExecutionResult.fatalError("pre-execution hook 失败: " + exception.getMessage());
        }
    }

    private ToolExecutionResult runPostExecutionHooks(
            ToolDefinition toolDefinition,
            Map<String, String> arguments,
            ToolExecutionResult executionResult
    ) {
        try {
            for (PostExecutionHook hook : postExecutionHooks) {
                hook.afterExecution(toolDefinition, arguments, executionResult);
            }
            return null;
        } catch (RuntimeException exception) {
            return ToolExecutionResult.fatalError("post-execution hook 失败: " + exception.getMessage());
        }
    }

    private ToolExecutionResult executeAfterApproval(
            ToolDefinition toolDefinition,
            Map<String, String> arguments,
            PermissionDecision permissionDecision
    ) {
        String reviewSummary = "";
        if (diffReviewService.requiresReview(toolDefinition.name())) {
            try {
                DiffReviewSummary diffReviewSummary = diffReviewService.review(toolDefinition.name(), arguments);
                reviewSummary = diffReviewSummary.summary();
            } catch (RuntimeException exception) {
                return ToolExecutionResult.fatalError("生成 diff 审查摘要失败: " + exception.getMessage());
            }
        }

        ApprovalDecision approvalDecision;
        try {
            approvalDecision = toolApprovalHandler.requestApproval(new ApprovalRequest(
                    toolDefinition,
                    arguments,
                    permissionDecision.reason(),
                    reviewSummary
            ));
        } catch (RuntimeException exception) {
            return ToolExecutionResult.fatalError("审批处理失败: " + exception.getMessage());
        }

        if (approvalDecision.disposition() == ToolApprovalHandler.Disposition.APPROVE) {
            ToolExecutionResult executionResult = executeRegisteredTool(toolDefinition.name(), arguments);
            if (executionResult == null) {
                return ToolExecutionResult.fatalError("工具返回了空结果: " + toolDefinition.name());
            }

            ToolExecutionResult postHookFailure = runPostExecutionHooks(toolDefinition, arguments, executionResult);
            if (postHookFailure != null) {
                return postHookFailure;
            }
            return executionResult;
        }

        StringBuilder builder = new StringBuilder("需要审批后才能执行工具: ")
                .append(toolDefinition.name())
                .append("。原因: ")
                .append(permissionDecision.reason())
                .append(System.lineSeparator())
                .append("审批结果: ")
                .append(approvalDecision.reason());
        if (!reviewSummary.isBlank()) {
            builder.append(System.lineSeparator())
                    .append(reviewSummary);
        }

        return ToolExecutionResult.recoverableError(builder.toString());
    }

    private String requireNonBlank(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    @FunctionalInterface
    public interface PreExecutionHook {

        void beforeExecution(ToolDefinition toolDefinition, Map<String, String> arguments);
    }

    @FunctionalInterface
    public interface PostExecutionHook {

        void afterExecution(ToolDefinition toolDefinition, Map<String, String> arguments, ToolExecutionResult executionResult);
    }
}
