package com.repopilot.cli.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.protocol.trace.AppendTraceEventRequest;
import com.repopilot.protocol.trace.TraceEventRecord;
import com.repopilot.protocol.trace.TraceEventType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultHttpTraceApiClientTest {

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
    void shouldAppendTraceEventViaHttpApi() throws Exception {
        httpServer.createContext("/api/sessions/session-001/trace-events", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(requestBody.contains("\"type\":\"MODEL_CALL_REQUESTED\""));
            assertTrue(requestBody.contains("\"source\":\"cli\""));
            assertTrue(requestBody.contains("\"summary\":\"第1步开始调用模型\""));
            assertTrue(requestBody.contains("\"stepNumber\":\"1\""));

            respondJson(exchange, """
                    {
                      "traceId": "trace-001",
                      "sessionId": "session-001",
                      "type": "MODEL_CALL_REQUESTED",
                      "source": "cli",
                      "summary": "第1步开始调用模型",
                      "occurredAt": "2026-04-17T08:00:00Z",
                      "metadata": {
                        "stepNumber": "1"
                      }
                    }
                    """);
        });
        httpServer.start();

        TraceApiClient client = new DefaultHttpTraceApiClient(baseUrl);
        TraceEventRecord record = client.appendTraceEvent(
                "session-001",
                new AppendTraceEventRequest(
                        TraceEventType.MODEL_CALL_REQUESTED,
                        "cli",
                        "第1步开始调用模型",
                        Instant.parse("2026-04-17T08:00:00Z"),
                        Map.of("stepNumber", "1")
                )
        );

        assertEquals("trace-001", record.traceId());
        assertEquals("session-001", record.sessionId());
        assertEquals(TraceEventType.MODEL_CALL_REQUESTED, record.type());
    }

    private void respondJson(HttpExchange exchange, String responseBody) throws IOException {
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
