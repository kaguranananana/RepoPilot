package com.repopilot.cli.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.protocol.json.ProtocolObjectMapperFactory;
import com.repopilot.protocol.trace.AppendTraceEventRequest;
import com.repopilot.protocol.trace.TraceEventRecord;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 基于 JDK HttpClient 的默认 trace API 客户端。
 * 这里保持和 session 客户端一致的最小实现风格，
 * 让 CLI 到 server 的协议层逻辑足够直接、可测试。
 */
public class DefaultHttpTraceApiClient implements TraceApiClient {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DefaultHttpTraceApiClient(String baseUrl) {
        this(baseUrl, HttpClient.newHttpClient(), ProtocolObjectMapperFactory.create());
    }

    DefaultHttpTraceApiClient(String baseUrl, HttpClient httpClient, ObjectMapper objectMapper) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null.");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null.");
    }

    @Override
    public TraceEventRecord appendTraceEvent(String sessionId, AppendTraceEventRequest request) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank.");
        }
        Objects.requireNonNull(request, "request must not be null.");

        try {
            // 先把结构化 trace 请求序列化成 JSON，
            // 保证 CLI 发给 server 的就是协议层约定好的统一格式。
            String requestBody = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/sessions/" + sessionId + "/trace-events"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            // 再统一走 send 方法，
            // 让状态码校验和 JSON 反序列化逻辑只维护一份。
            return send(httpRequest, TraceEventRecord.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize append trace event request.", exception);
        }
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
