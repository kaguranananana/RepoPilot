package com.repopilot.server.session;

import com.repopilot.protocol.plan.PlanRecord;
import com.repopilot.protocol.plan.PlanStatus;
import java.time.Instant;
import java.util.Optional;

/**
 * Plan 状态持久化边界。
 */
public interface PlanRepository {

    void save(PlanRecord plan);

    Optional<PlanRecord> findLatestBySessionId(String sessionId);

    Optional<PlanRecord> findBySessionIdAndPlanId(String sessionId, String planId);

    void updateStatus(String sessionId, String planId, PlanStatus status, Instant updatedAt);
}
