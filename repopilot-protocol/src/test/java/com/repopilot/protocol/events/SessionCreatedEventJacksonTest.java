package com.repopilot.protocol.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.protocol.json.ProtocolObjectMapperFactory;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SessionCreatedEventJacksonTest {

    @Test
    void shouldSerializeAndDeserializeSessionCreatedEvent() throws Exception {
        Instant occurredAt = Instant.parse("2026-04-15T08:00:00Z");
        SessionCreatedEvent event = new SessionCreatedEvent(
                "session-001",
                "workspace-001",
                "cli",
                occurredAt
        );

        String json = ProtocolObjectMapperFactory.create().writeValueAsString(event);
        SessionCreatedEvent restored = ProtocolObjectMapperFactory.create()
                .readValue(json, SessionCreatedEvent.class);

        assertTrue(json.contains("\"sessionId\":\"session-001\""));
        assertTrue(json.contains("\"workspaceId\":\"workspace-001\""));
        assertEquals(event, restored);
    }
}

