package com.repopilot.server.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.protocol.trace.TraceEventRecord;
import com.repopilot.protocol.trace.TraceEventType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 基于 PostgreSQL 的 Trace 事件仓储实现。
 * metadata 以 JSON 字符串保存，避免把事件扩展字段拆成不稳定的列结构。
 */
@Repository
public class JdbcTraceEventRepository implements TraceEventRepository {

    private static final TypeReference<Map<String, String>> METADATA_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcTraceEventRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(TraceEventRecord record) {
        jdbcTemplate.update(
                """
                        INSERT INTO trace_events (
                            trace_id,
                            session_id,
                            type,
                            source,
                            summary,
                            occurred_at,
                            metadata_json
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                record.traceId(),
                record.sessionId(),
                record.type().name(),
                record.source(),
                record.summary(),
                Timestamp.from(record.occurredAt()),
                writeMetadata(record.metadata())
        );
    }

    @Override
    public List<TraceEventRecord> findBySessionId(String sessionId) {
        return jdbcTemplate.query(
                """
                        SELECT
                            trace_id,
                            session_id,
                            type,
                            source,
                            summary,
                            occurred_at,
                            metadata_json
                        FROM trace_events
                        WHERE session_id = ?
                        ORDER BY sequence_no ASC
                        """,
                this::mapTraceEvent,
                sessionId
        );
    }

    private TraceEventRecord mapTraceEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        // 回放对象必须完全来自落库字段，不能重新生成 traceId 或 occurredAt。
        return new TraceEventRecord(
                resultSet.getString("trace_id"),
                resultSet.getString("session_id"),
                TraceEventType.valueOf(resultSet.getString("type")),
                resultSet.getString("source"),
                resultSet.getString("summary"),
                readInstant(resultSet, "occurred_at"),
                readMetadata(resultSet.getString("metadata_json"))
        );
    }

    private String writeMetadata(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Trace metadata 序列化失败: " + exception.getMessage(), exception);
        }
    }

    private Map<String, String> readMetadata(String metadataJson) {
        try {
            return objectMapper.readValue(metadataJson, METADATA_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Trace metadata 反序列化失败: " + exception.getMessage(), exception);
        }
    }

    private Instant readInstant(ResultSet resultSet, String columnName) throws SQLException {
        return resultSet.getTimestamp(columnName).toInstant();
    }
}
