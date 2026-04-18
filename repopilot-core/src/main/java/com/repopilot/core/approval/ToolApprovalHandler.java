package com.repopilot.core.approval;

import com.repopilot.core.tool.ToolDefinition;
import java.util.Map;
import java.util.Objects;

/**
 * 工具审批处理器。
 * 它只负责回答“当前待审批工具是否放行”，
 * 不直接参与权限判定，也不直接执行工具。
 */
@FunctionalInterface
public interface ToolApprovalHandler {

    ApprovalDecision requestApproval(ApprovalRequest request);

    static ToolApprovalHandler denyAll() {
        // 非交互场景默认保持 fail-closed，
        // 这样单次运行、测试或后续 server 入口在没有显式审批器时，
        // 都不会悄悄把高风险工具放行。
        return request -> ApprovalDecision.deny("当前运行模式不支持交互式审批。");
    }

    /**
     * 单次审批请求。
     * reviewSummary 目前直接承载 diff review 的稳定文本摘要，
     * 这样 CLI 终端和后续控制面都能先用同一份审查内容。
     */
    record ApprovalRequest(
            ToolDefinition toolDefinition,
            Map<String, String> arguments,
            String permissionReason,
            String reviewSummary
    ) {

        public ApprovalRequest {
            toolDefinition = Objects.requireNonNull(toolDefinition, "toolDefinition must not be null.");
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
            permissionReason = requireNonBlank(permissionReason, "permissionReason must not be blank.");
            reviewSummary = reviewSummary == null ? "" : reviewSummary.strip();
        }
    }

    /**
     * 单次审批结果。
     * disposition 只保留 approve / deny 两态，
     * 让治理层继续统一负责把它翻译成后续执行或结构化错误结果。
     */
    record ApprovalDecision(
            Disposition disposition,
            String reason
    ) {

        public ApprovalDecision {
            disposition = Objects.requireNonNull(disposition, "disposition must not be null.");
            reason = requireNonBlank(reason, "reason must not be blank.");
        }

        public static ApprovalDecision approve(String reason) {
            return new ApprovalDecision(Disposition.APPROVE, reason);
        }

        public static ApprovalDecision deny(String reason) {
            return new ApprovalDecision(Disposition.DENY, reason);
        }
    }

    enum Disposition {
        APPROVE,
        DENY
    }

    private static String requireNonBlank(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
