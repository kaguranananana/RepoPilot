package com.repopilot.cli.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.protocol.session.CreateSessionRequest;
import com.repopilot.protocol.session.SessionStatus;
import com.repopilot.protocol.session.SessionSummary;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultHttpSessionApiClientTest {

    private HttpServer httpServer;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        httpServer.stop(0);
    }

    @Test
    void shouldCreateSessionViaHttpApi() throws Exception {
        httpServer.createContext("/api/sessions", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(requestBody.contains("\"workspaceId\":\"workspace-001\""));
            assertTrue(requestBody.contains("\"requestedBy\":\"cli\""));

            respondJson(exchange, """
                    {
                      "sessionId": "session-001",
                      "workspaceId": "workspace-001",
                      "requestedBy": "cli",
                      "status": "CREATED",
                      "createdAt": "2026-04-15T08:00:00Z",
                      "updatedAt": "2026-04-15T08:00:00Z"
                    }
                    """);
        });
        httpServer.start();

        SessionApiClient client = new DefaultHttpSessionApiClient(baseUrl);
        SessionSummary summary = client.createSession(new CreateSessionRequest("workspace-001", "cli"));

        assertEquals("session-001", summary.sessionId());
        assertEquals(SessionStatus.CREATED, summary.status());
        assertEquals(Instant.parse("2026-04-15T08:00:00Z"), summary.createdAt());
    }

    @Test
    void shouldFetchSessionViaHttpApi() throws Exception {
        httpServer.createContext("/api/sessions/session-002", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());

            respondJson(exchange, """
                    {
                      "sessionId": "session-002",
                      "workspaceId": "workspace-002",
                      "requestedBy": "cli",
                      "status": "RUNNING",
                      "createdAt": "2026-04-15T08:00:00Z",
                      "updatedAt": "2026-04-15T08:05:00Z"
                    }
                    """);
        });
        httpServer.start();

        SessionApiClient client = new DefaultHttpSessionApiClient(baseUrl);
        SessionSummary summary = client.getSession("session-002");

        assertEquals("session-002", summary.sessionId());
        assertEquals("workspace-002", summary.workspaceId());
        assertEquals(SessionStatus.RUNNING, summary.status());
    }

    private void respondJson(HttpExchange exchange, String responseBody) throws IOException {
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
