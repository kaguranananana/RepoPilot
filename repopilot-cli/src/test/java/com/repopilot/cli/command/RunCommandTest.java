package com.repopilot.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.cli.RepoPilotCliCommand;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class RunCommandTest {

    private HttpServer httpServer;
    private String baseUrl;
    private String lastRequestBody;
    private List<String> traceRequestBodies;

    @BeforeEach
    void setUp() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
        traceRequestBodies = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        httpServer.stop(0);
    }

    @Test
    void shouldRegisterRunSubcommandOnRootCommand() {
        CommandLine commandLine = new CommandLine(new RepoPilotCliCommand());

        assertTrue(commandLine.getSubcommands().containsKey("run"));
    }

    @Test
    void shouldCreateSessionAndPrintRuntimeAnswer() throws Exception {
        httpServer.createContext("/api/sessions", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

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
        httpServer.createContext("/api/sessions/session-001/trace-events", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            traceRequestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            respondJson(exchange, """
                    {
                      "traceId": "trace-001",
                      "sessionId": "session-001",
                      "type": "MODEL_CALL_REQUESTED",
                      "source": "cli",
                      "summary": "trace accepted",
                      "occurredAt": "2026-04-15T08:00:01Z",
                      "metadata": {}
                    }
                    """);
        });
        httpServer.start();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CommandLine commandLine = new CommandLine(new RunCommand());
        commandLine.setOut(new PrintWriter(outputStream, true, StandardCharsets.UTF_8));

        int exitCode = commandLine.execute(
                "--workspace-id", "workspace-001",
                "--server-base-url", baseUrl,
                "--prompt", "分析 pom.xml"
        );

        assertEquals(0, exitCode);
        assertTrue(lastRequestBody.contains("\"workspaceId\":\"workspace-001\""));
        assertTrue(lastRequestBody.contains("\"requestedBy\":\"cli\""));
        assertEquals(2, traceRequestBodies.size());
        assertTrue(traceRequestBodies.get(0).contains("\"type\":\"MODEL_CALL_REQUESTED\""));
        assertTrue(traceRequestBodies.get(1).contains("\"type\":\"MODEL_RESPONSE_RECEIVED\""));
        assertTrue(traceRequestBodies.get(0).contains("\"source\":\"cli\""));
        assertEquals(
                "RepoPilot runtime accepted prompt for session session-001: 分析 pom.xml",
                outputStream.toString(StandardCharsets.UTF_8).trim()
        );
    }

    private void respondJson(HttpExchange exchange, String responseBody) throws IOException {
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
