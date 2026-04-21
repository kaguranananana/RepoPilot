package com.repopilot.protocol.plan;

/**
 * 创建计划记录的请求模型。
 * turnId 允许为空，因为早期 CLI 还没有把 turn 持久化为独立资源。
 */
public record CreatePlanRequest(
        String turnId,
        String content
) {
}
