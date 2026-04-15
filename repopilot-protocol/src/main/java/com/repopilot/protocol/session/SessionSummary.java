package com.repopilot.protocol.session;

import java.time.Instant;

/**
 * 会话摘要是控制面展示和查询的基础模型。
 * 它不承载完整消息历史，只保留足够描述一次运行的核心元数据。
 */
public record SessionSummary(
        String sessionId,
        String workspaceId,
        String requestedBy,
        SessionStatus status,
        Instant createdAt,
        Instant updatedAt
) {

    public SessionSummary withUpdatedAt(Instant nextUpdatedAt) {
        return new SessionSummary(
                sessionId,
                workspaceId,
                requestedBy,
                status,
                createdAt,
                nextUpdatedAt
        );
    }

    public SessionSummary withStatus(SessionStatus nextStatus, Instant nextUpdatedAt) {
        return new SessionSummary(
                sessionId,
                workspaceId,
                requestedBy,
                nextStatus,
                createdAt,
                nextUpdatedAt
        );
    }
}

