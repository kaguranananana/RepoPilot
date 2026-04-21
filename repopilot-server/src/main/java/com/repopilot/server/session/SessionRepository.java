package com.repopilot.server.session;

import com.repopilot.protocol.session.SessionSummary;
import java.time.Instant;
import java.util.Optional;

/**
 * Session 持久化边界。
 * Service 只依赖这个接口，不再关心底层是内存 Map 还是 PostgreSQL 表。
 */
public interface SessionRepository {

    void save(SessionSummary session);

    Optional<SessionSummary> findById(String sessionId);

    void updateUpdatedAt(String sessionId, Instant updatedAt);
}
