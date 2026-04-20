package com.repopilot.core.agent;

import com.repopilot.core.permission.WorkspacePermissionPolicy;
import com.repopilot.core.tool.ToolDefinition;
import java.util.List;
import java.util.Objects;

/**
 * Agent 单回合运行模式。
 * PLAN 是只读计划阶段，EXECUTE 是默认执行阶段。
 */
public enum AgentRunMode {
    PLAN,
    EXECUTE;

    public boolean allowsTool(String toolName) {
        String safeToolName = requireNonBlank(toolName, "toolName must not be blank.");
        return switch (this) {
            // PLAN 模式只允许工作区权限策略明确标记为只读的工具。
            case PLAN -> WorkspacePermissionPolicy.isReadOnlyToolName(safeToolName);
            // EXECUTE 模式不在运行模式层收缩工具，后续仍交给权限策略和审批链路治理。
            case EXECUTE -> true;
        };
    }

    public List<ToolDefinition> filterAvailableTools(List<ToolDefinition> toolDefinitions) {
        Objects.requireNonNull(toolDefinitions, "toolDefinitions must not be null.");
        return toolDefinitions.stream()
                // 这里按运行模式做第一层工具子集收缩，
                // 后续 Skill allowed-tools 会在这个结果之上继续求交集。
                .filter(toolDefinition -> allowsTool(toolDefinition.name()))
                .toList();
    }

    public String allowedToolSummary() {
        return switch (this) {
            case PLAN -> String.join(", ", WorkspacePermissionPolicy.readOnlyToolNames());
            case EXECUTE -> "当前 prompt 中暴露的有效工具子集";
        };
    }

    private static String requireNonBlank(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
