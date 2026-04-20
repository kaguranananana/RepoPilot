package com.repopilot.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.cli.RepoPilotCliCommand;
import com.repopilot.cli.runtime.CliRuntimeBootstrap;
import com.repopilot.cli.session.SessionApiClient;
import com.repopilot.core.trace.TracePublisher;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import com.repopilot.protocol.session.CreateSessionRequest;
import com.repopilot.protocol.session.SessionStatus;
import com.repopilot.protocol.session.SessionSummary;
import com.repopilot.protocol.trace.TraceEventRecord;

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

    @Test
    void shouldPassExplicitMaxStepsToRuntimeBootstrap() {
        CapturingRuntimeBootstrap runtimeBootstrap = new CapturingRuntimeBootstrap();
        RunCommand command = new RunCommand(
                baseUrl -> new FixedSessionApiClient(),
                baseUrl -> (sessionId, request) -> new TraceEventRecord(
                        "trace-001",
                        sessionId,
                        request.type(),
                        request.source(),
                        request.summary(),
                        request.occurredAt(),
                        request.metadata()
                ),
                runtimeBootstrap
        );
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CommandLine commandLine = new CommandLine(command);
        commandLine.setOut(new PrintWriter(outputStream, true, StandardCharsets.UTF_8));

        int exitCode = commandLine.execute(
                "--workspace-id", "workspace-001",
                "--server-base-url", "http://127.0.0.1:8080",
                "--prompt", "分析 pom.xml",
                "--max-steps", "16"
        );

        assertEquals(0, exitCode);
        assertEquals(16, runtimeBootstrap.maxSteps);
        assertEquals("分析 pom.xml", runtimeBootstrap.prompt);
        assertEquals("ok", outputStream.toString(StandardCharsets.UTF_8).trim());
    }

    private void respondJson(HttpExchange exchange, String responseBody) throws IOException {
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static final class FixedSessionApiClient implements SessionApiClient {

        @Override
        public SessionSummary createSession(CreateSessionRequest request) {
            return new SessionSummary(
                    "session-001",
                    request.workspaceId(),
                    request.requestedBy(),
                    SessionStatus.CREATED,
                    Instant.parse("2026-04-20T08:00:00Z"),
                    Instant.parse("2026-04-20T08:00:00Z")
            );
        }

        @Override
        public SessionSummary getSession(String sessionId) {
            throw new UnsupportedOperationException("测试不需要查询 session。");
        }
    }

    private static final class CapturingRuntimeBootstrap implements CliRuntimeBootstrap {

        private String prompt;
        private int maxSteps;

        @Override
        public String run(
                SessionSummary sessionSummary,
                String prompt,
                TracePublisher tracePublisher,
                int maxSteps
        ) {
            this.prompt = prompt;
            this.maxSteps = maxSteps;
            return "ok";
        }
    }
}
