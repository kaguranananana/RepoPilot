package com.repopilot.protocol.events;

import java.time.Instant;

/**
 * 表示一次新的 agent 会话已经被创建。
 * 这个事件会在 CLI 向 server 注册 session 成功后产生，
 * 是后续 trace、approval、diff summary 关联的起点。
 */
public record SessionCreatedEvent(
        String sessionId,
        String workspaceId,
        String requestedBy,
        Instant occurredAt
) implements SessionEvent {
}

