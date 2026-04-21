package com.repopilot.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.protocol.json.ProtocolObjectMapperFactory;
import com.repopilot.protocol.session.SessionStatus;
import com.repopilot.protocol.session.SessionSummary;
import com.repopilot.protocol.trace.TraceEventRecord;
import com.repopilot.protocol.trace.TraceEventType;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class JdbcSessionPersistenceTests {

    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUpSchema() throws Exception {
        dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:%s;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1".formatted(
                        getClass().getSimpleName() + "-" + System.nanoTime()
                ),
                "",
                "",
                true
        );
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource("db/migration/V1__create_session_trace_tables.sql")
            );
        }
    }

    @AfterEach
    void closeDataSource() {
        dataSource.destroy();
    }

    @Test
    void shouldPersistSessionAcrossRepositoryInstances() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        SessionRepository firstRepository = new JdbcSessionRepository(jdbcTemplate);
        SessionRepository secondRepository = new JdbcSessionRepository(jdbcTemplate);
        SessionSummary session = new SessionSummary(
                "session-persisted",
                "workspace-001",
                "cli",
                SessionStatus.CREATED,
                Instant.parse("2026-04-21T01:00:00Z"),
                Instant.parse("2026-04-21T01:00:00Z")
        );

        firstRepository.save(session);

        assertEquals(session, secondRepository.findById("session-persisted").orElseThrow());
    }

    @Test
    void shouldAppendTraceEventsAndReadThemBackInInsertOrder() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        SessionRepository sessionRepository = new JdbcSessionRepository(jdbcTemplate);
        TraceEventRepository traceEventRepository = new JdbcTraceEventRepository(
                jdbcTemplate,
                ProtocolObjectMapperFactory.create()
        );
        sessionRepository.save(new SessionSummary(
                "session-trace",
                "workspace-002",
                "cli",
                SessionStatus.CREATED,
                Instant.parse("2026-04-21T02:00:00Z"),
                Instant.parse("2026-04-21T02:00:00Z")
        ));

        traceEventRepository.save(new TraceEventRecord(
                "trace-001",
                "session-trace",
                TraceEventType.MODEL_CALL_REQUESTED,
                "cli",
                "开始调用模型",
                Instant.parse("2026-04-21T02:00:01Z"),
                Map.of("stepNumber", "1")
        ));
        traceEventRepository.save(new TraceEventRecord(
                "trace-002",
                "session-trace",
                TraceEventType.TOOL_CALL_COMPLETED,
                "cli",
                "完成工具调用",
                Instant.parse("2026-04-21T02:00:01Z"),
                Map.of("tool", "read_file", "status", "SUCCESS")
        ));

        List<TraceEventRecord> events = traceEventRepository.findBySessionId("session-trace");

        assertEquals(List.of("trace-001", "trace-002"), events.stream().map(TraceEventRecord::traceId).toList());
        assertEquals("read_file", events.get(1).metadata().get("tool"));
        assertTrue(traceEventRepository.findBySessionId("missing-session").isEmpty());
    }
}
