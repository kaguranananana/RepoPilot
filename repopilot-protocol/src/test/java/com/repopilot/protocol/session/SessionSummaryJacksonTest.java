package com.repopilot.protocol.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.protocol.json.ProtocolObjectMapperFactory;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SessionSummaryJacksonTest {

    @Test
    void shouldSerializeAndDeserializeSessionSummary() throws Exception {
        SessionSummary summary = new SessionSummary(
                "session-001",
                "workspace-001",
                "cli",
                SessionStatus.CREATED,
                Instant.parse("2026-04-15T08:00:00Z"),
                Instant.parse("2026-04-15T08:00:00Z")
        );

        String json = ProtocolObjectMapperFactory.create().writeValueAsString(summary);
        SessionSummary restored = ProtocolObjectMapperFactory.create()
                .readValue(json, SessionSummary.class);

        assertTrue(json.contains("\"status\":\"CREATED\""));
        assertTrue(json.contains("\"requestedBy\":\"cli\""));
        assertEquals(summary, restored);
    }
}

