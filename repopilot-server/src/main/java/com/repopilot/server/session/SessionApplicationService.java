package com.repopilot.server.session;

import com.repopilot.protocol.session.CreateSessionRequest;
import com.repopilot.protocol.session.SessionStatus;
import com.repopilot.protocol.session.SessionSummary;
import com.repopilot.protocol.trace.AppendTraceEventRequest;
import com.repopilot.protocol.trace.TraceEventRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Service;

/**
 * 控制面的最小应用服务。
 * 阶段 0 先用进程内存把会话和 trace 流程打通，
 * 后续迁移到 JPA 时，controller 和协议层都不需要推倒重来。
 */
@Service
public class SessionApplicationService {

    private final Clock clock;
    private final ConcurrentMap<String, SessionSummary> sessionStore = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CopyOnWriteArrayList<TraceEventRecord>> traceStore =
            new ConcurrentHashMap<>();

    public SessionApplicationService(Clock clock) {
        this.clock = clock;
    }

    public SessionSummary createSession(CreateSessionRequest request) {
        validateCreateSessionRequest(request);

        Instant now = Instant.now(clock);
        String sessionId = "session-" + UUID.randomUUID();
        SessionSummary summary = new SessionSummary(
                sessionId,
                request.workspaceId().trim(),
                request.requestedBy().trim(),
                SessionStatus.CREATED,
                now,
                now
        );

        // 先注册 session，确保后续所有 trace 都能通过 sessionId 归档到同一条主线。
        sessionStore.put(summary.sessionId(), summary);
        // 同步初始化 trace 列表，避免第一次追加事件时出现“有 session 没有容器”的状态分叉。
        traceStore.put(summary.sessionId(), new CopyOnWriteArrayList<>());

        return summary;
    }

    public SessionSummary getSession(String sessionId) {
        return requireSession(sessionId);
    }

    public TraceEventRecord appendTraceEvent(String sessionId, AppendTraceEventRequest request) {
        validateAppendTraceRequest(request);
        SessionSummary session = requireSession(sessionId);

        TraceEventRecord record = new TraceEventRecord(
                "trace-" + UUID.randomUUID(),
                sessionId,
                request.type(),
                request.source().trim(),
                request.summary().trim(),
                request.occurredAt(),
                request.metadata()
        );

        // 先把事件追加到该 session 的 trace 列表中，确保回放查询按真实发生顺序可见。
        traceStore.get(sessionId).add(record);

        // trace 到来说明会话刚发生过动作，因此把 updatedAt 推进到事件发生时间。
        sessionStore.put(sessionId, session.withUpdatedAt(record.occurredAt()));

        return record;
    }

    public List<TraceEventRecord> listTraceEvents(String sessionId) {
        requireSession(sessionId);
        return List.copyOf(traceStore.get(sessionId));
    }

    private SessionSummary requireSession(String sessionId) {
        SessionSummary session = sessionStore.get(sessionId);
        if (session == null) {
            throw new SessionNotFoundException(sessionId);
        }
        return session;
    }

    private void validateCreateSessionRequest(CreateSessionRequest request) {
        Objects.requireNonNull(request, "CreateSessionRequest must not be null.");
        requireNonBlank(request.workspaceId(), "workspaceId must not be blank.");
        requireNonBlank(request.requestedBy(), "requestedBy must not be blank.");
    }

    private void validateAppendTraceRequest(AppendTraceEventRequest request) {
        Objects.requireNonNull(request, "AppendTraceEventRequest must not be null.");
        Objects.requireNonNull(request.type(), "type must not be null.");
        requireNonBlank(request.source(), "source must not be blank.");
        requireNonBlank(request.summary(), "summary must not be blank.");
        Objects.requireNonNull(request.occurredAt(), "occurredAt must not be null.");
    }

    private void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}

