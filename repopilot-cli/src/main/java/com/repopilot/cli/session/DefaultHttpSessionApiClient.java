package com.repopilot.cli.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.protocol.json.ProtocolObjectMapperFactory;
import com.repopilot.protocol.session.CreateSessionRequest;
import com.repopilot.protocol.session.SessionSummary;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 基于 JDK HttpClient 的默认 session API 客户端。
 * 这一层故意不依赖 Spring，只保留最小 HTTP 访问能力，
 * 这样 CLI 模块可以继续保持轻量。
 */
public class DefaultHttpSessionApiClient implements SessionApiClient {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DefaultHttpSessionApiClient(String baseUrl) {
        this(baseUrl, HttpClient.newHttpClient(), ProtocolObjectMapperFactory.create());
    }

    DefaultHttpSessionApiClient(String baseUrl, HttpClient httpClient, ObjectMapper objectMapper) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null.");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null.");
    }

    @Override
    public SessionSummary createSession(CreateSessionRequest request) {
        Objects.requireNonNull(request, "request must not be null.");

        try {
            String requestBody = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/sessions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            return send(httpRequest, SessionSummary.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize create session request.", exception);
        }
    }

    @Override
    public SessionSummary getSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank.");
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/sessions/" + sessionId))
                .GET()
                .build();

        return send(httpRequest, SessionSummary.class);
    }

    private <T> T send(HttpRequest request, Class<T> responseType) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "HTTP request failed, status=%d, body=%s".formatted(response.statusCode(), response.body())
                );
            }

            return objectMapper.readValue(response.body(), responseType);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse HTTP response.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP request interrupted.", exception);
        }
    }

    private String normalizeBaseUrl(String rawBaseUrl) {
        if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank.");
        }

        String trimmed = rawBaseUrl.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
