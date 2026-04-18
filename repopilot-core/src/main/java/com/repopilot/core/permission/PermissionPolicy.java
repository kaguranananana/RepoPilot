package com.repopilot.core.permission;

import com.repopilot.core.tool.ToolDefinition;
import java.util.Map;
import java.util.Objects;

/**
 * 工具权限策略接口。
 * 它只负责回答“这次工具调用在当前策略下是拒绝、待审批还是允许”，
 * 不直接执行工具，也不吞掉真实错误。
 */
@FunctionalInterface
public interface PermissionPolicy {

    PermissionDecision evaluate(ToolDefinition toolDefinition, Map<String, String> arguments);

    static PermissionPolicy allowAll() {
        // 这个策略只用于单元测试或显式放开的调用场景，
        // 让治理流水线本身可以被独立验证，
        // 同时避免把“是否启用严格工作区策略”硬编码进 AgentLoop。
        return (toolDefinition, arguments) -> PermissionDecision.allow(
                "当前权限策略显式允许该工具执行。"
        );
    }

    /**
     * 单次权限判定结果。
     * disposition 决定治理层接下来是直接拒绝、等待审批还是继续执行。
     */
    record PermissionDecision(
            PermissionDisposition disposition,
            String reason
    ) {

        public PermissionDecision {
            disposition = Objects.requireNonNull(disposition, "disposition must not be null.");
            reason = requireNonBlank(reason, "reason must not be blank.");
        }

        public static PermissionDecision deny(String reason) {
            return new PermissionDecision(PermissionDisposition.DENY, reason);
        }

        public static PermissionDecision ask(String reason) {
            return new PermissionDecision(PermissionDisposition.ASK, reason);
        }

        public static PermissionDecision allow(String reason) {
            return new PermissionDecision(PermissionDisposition.ALLOW, reason);
        }

        private static String requireNonBlank(String value, String message) {
            Objects.requireNonNull(value, message);
            if (value.isBlank()) {
                throw new IllegalArgumentException(message);
            }
            return value.strip();
        }
    }

    /**
     * 权限决策顺序固定为 deny -> ask -> allow。
     * 这样任何硬拒绝都会优先于审批判断和最终放行。
     */
    enum PermissionDisposition {
        DENY,
        ASK,
        ALLOW
    }
}
