package com.repopilot.server.session;

import com.repopilot.protocol.plan.PlanRecord;
import com.repopilot.protocol.plan.PlanStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 基于 PostgreSQL 的 Plan 仓储实现。
 */
@Repository
public class JdbcPlanRepository implements PlanRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcPlanRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(PlanRecord plan) {
        jdbcTemplate.update(
                """
                        INSERT INTO plans (
                            plan_id,
                            session_id,
                            turn_id,
                            content,
                            status,
                            created_at,
                            updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                plan.planId(),
                plan.sessionId(),
                plan.turnId(),
                plan.content(),
                plan.status().name(),
                Timestamp.from(plan.createdAt()),
                Timestamp.from(plan.updatedAt())
        );
    }

    @Override
    public Optional<PlanRecord> findLatestBySessionId(String sessionId) {
        List<PlanRecord> matches = jdbcTemplate.query(
                """
                        SELECT
                            plan_id,
                            session_id,
                            turn_id,
                            content,
                            status,
                            created_at,
                            updated_at
                        FROM plans
                        WHERE session_id = ?
                        ORDER BY created_at DESC, plan_id DESC
                        LIMIT 1
                        """,
                this::mapPlan,
                sessionId
        );
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    @Override
    public Optional<PlanRecord> findBySessionIdAndPlanId(String sessionId, String planId) {
        List<PlanRecord> matches = jdbcTemplate.query(
                """
                        SELECT
                            plan_id,
                            session_id,
                            turn_id,
                            content,
                            status,
                            created_at,
                            updated_at
                        FROM plans
                        WHERE session_id = ?
                          AND plan_id = ?
                        """,
                this::mapPlan,
                sessionId,
                planId
        );
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    @Override
    public void updateStatus(String sessionId, String planId, PlanStatus status, Instant updatedAt) {
        jdbcTemplate.update(
                """
                        UPDATE plans
                        SET status = ?,
                            updated_at = ?
                        WHERE session_id = ?
                          AND plan_id = ?
                        """,
                status.name(),
                Timestamp.from(updatedAt),
                sessionId,
                planId
        );
    }

    private PlanRecord mapPlan(ResultSet resultSet, int rowNumber) throws SQLException {
        // 计划状态是恢复 Plan / Execute 的事实来源，不能在读取时重新推断。
        return new PlanRecord(
                resultSet.getString("plan_id"),
                resultSet.getString("session_id"),
                resultSet.getString("turn_id"),
                resultSet.getString("content"),
                PlanStatus.valueOf(resultSet.getString("status")),
                readInstant(resultSet, "created_at"),
                readInstant(resultSet, "updated_at")
        );
    }

    private Instant readInstant(ResultSet resultSet, String columnName) throws SQLException {
        return resultSet.getTimestamp(columnName).toInstant();
    }
}
