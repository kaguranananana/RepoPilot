package com.repopilot.protocol.plan;

import java.time.Instant;

/**
 * 控制面持久化后的计划记录。
 */
public record PlanRecord(
        String planId,
        String sessionId,
        String turnId,
        String content,
        PlanStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
