package com.repopilot.core.permission;

import com.repopilot.core.tool.ToolDefinition;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 工作区级权限策略。
 * 当前阶段只显式识别一期内置工具：
 * 1. 只读工具在工作区内可直接放行
 * 2. 写文件和执行命令必须先审批
 * 3. 未声明的工具默认 fail-closed 拒绝
 */
public final class WorkspacePermissionPolicy implements PermissionPolicy {

    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "read_file",
            "grep_files"
    );
    private static final Set<String> CONTEXT_TOOLS = Set.of(
            "activate_skill"
    );
    private static final Set<String> APPROVAL_REQUIRED_TOOLS = Set.of(
            "run_command",
            "write_file"
    );

    private final Path workspaceRoot;

    public WorkspacePermissionPolicy(Path workspaceRoot) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null.")
                .toAbsolutePath()
                .normalize();
    }

    @Override
    public PermissionDecision evaluate(ToolDefinition toolDefinition, Map<String, String> arguments) {
        Objects.requireNonNull(toolDefinition, "toolDefinition must not be null.");
        Map<String, String> safeArguments = arguments == null ? Map.of() : Map.copyOf(arguments);

        // 先解析 path 参数并做工作区边界检查，
        // 这样任何越界访问都会在审批判断前被硬拒绝，
        // 严格满足 deny -> ask -> allow 的优先级顺序。
        Path resolvedPath = resolvePathArgument(safeArguments);
        if (resolvedPath != null && !resolvedPath.startsWith(workspaceRoot)) {
            return PermissionDecision.deny("工具不能访问工作区外路径: " + safeArguments.get("path"));
        }

        // 写文件和命令执行都属于高风险能力，
        // 当前阶段没有审批流完成前，一律只返回 ASK，不直接落盘或执行命令。
        if (APPROVAL_REQUIRED_TOOLS.contains(toolDefinition.name())) {
            return PermissionDecision.ask(resolveApprovalReason(toolDefinition.name()));
        }

        // 一期只显式放行工作区内的只读工具，
        // 保证主链路先把最安全、最确定的能力跑通。
        if (READ_ONLY_TOOLS.contains(toolDefinition.name())) {
            return PermissionDecision.allow("工具只读且作用域位于当前工作区内。");
        }

        // 上下文工具只会装配运行时消息，
        // 不直接访问文件系统、写文件或执行命令，
        // 因此允许它在治理链路中直接通过。
        if (CONTEXT_TOOLS.contains(toolDefinition.name())) {
            return PermissionDecision.allow("工具只修改当前会话上下文，不直接触碰工作区资源。");
        }

        // 未声明工具统一 fail-closed，
        // 避免动态工具集悄悄绕过全局权限边界。
        return PermissionDecision.deny("工具未被全局权限策略声明，默认 fail-closed 拒绝: " + toolDefinition.name());
    }

    private String resolveApprovalReason(String toolName) {
        return switch (toolName) {
            case "run_command" -> "命令执行属于高风险能力，当前阶段必须先审批。";
            case "write_file" -> "写文件操作必须先生成 diff 摘要并等待审批。";
            default -> throw new IllegalArgumentException("Unsupported approval-required tool: " + toolName);
        };
    }

    private Path resolvePathArgument(Map<String, String> arguments) {
        String pathArgument = arguments.get("path");
        if (pathArgument == null || pathArgument.isBlank()) {
            return null;
        }

        Path candidatePath = Path.of(pathArgument.strip());
        if (candidatePath.isAbsolute()) {
            return candidatePath.normalize();
        }
        return workspaceRoot.resolve(candidatePath).normalize();
    }
}
