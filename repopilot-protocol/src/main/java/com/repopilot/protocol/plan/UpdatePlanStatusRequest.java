package com.repopilot.protocol.plan;

/**
 * 更新计划状态的请求模型。
 */
public record UpdatePlanStatusRequest(
        PlanStatus status
) {
}
