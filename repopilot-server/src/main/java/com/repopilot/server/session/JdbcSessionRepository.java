package com.repopilot.server.session;

import com.repopilot.protocol.session.SessionStatus;
import com.repopilot.protocol.session.SessionSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 基于 PostgreSQL 的 Session 仓储实现。
 * 当前只持久化控制面已经暴露的最小 SessionSummary 字段。
 */
@Repository
public class JdbcSessionRepository implements SessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(SessionSummary session) {
        jdbcTemplate.update(
                """
                        INSERT INTO sessions (
                            session_id,
                            workspace_id,
                            requested_by,
                            status,
                            created_at,
                            updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                session.sessionId(),
                session.workspaceId(),
                session.requestedBy(),
                session.status().name(),
                Timestamp.from(session.createdAt()),
                Timestamp.from(session.updatedAt())
        );
    }

    @Override
    public Optional<SessionSummary> findById(String sessionId) {
        List<SessionSummary> matches = jdbcTemplate.query(
                """
                        SELECT
                            session_id,
                            workspace_id,
                            requested_by,
                            status,
                            created_at,
                            updated_at
                        FROM sessions
                        WHERE session_id = ?
                        """,
                this::mapSession,
                sessionId
        );
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    @Override
    public void updateUpdatedAt(String sessionId, Instant updatedAt) {
        jdbcTemplate.update(
                """
                        UPDATE sessions
                        SET updated_at = ?
                        WHERE session_id = ?
                        """,
                Timestamp.from(updatedAt),
                sessionId
        );
    }

    private SessionSummary mapSession(ResultSet resultSet, int rowNumber) throws SQLException {
        // 每一列都显式映射，避免数据库字段和协议 DTO 之间出现隐式约定。
        return new SessionSummary(
                resultSet.getString("session_id"),
                resultSet.getString("workspace_id"),
                resultSet.getString("requested_by"),
                SessionStatus.valueOf(resultSet.getString("status")),
                readInstant(resultSet, "created_at"),
                readInstant(resultSet, "updated_at")
        );
    }

    private Instant readInstant(ResultSet resultSet, String columnName) throws SQLException {
        return resultSet.getTimestamp(columnName).toInstant();
    }
}
