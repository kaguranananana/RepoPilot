package com.repopilot.server.session;

import com.repopilot.protocol.plan.CreatePlanRequest;
import com.repopilot.protocol.plan.PlanRecord;
import com.repopilot.protocol.plan.PlanStatus;
import com.repopilot.protocol.plan.UpdatePlanStatusRequest;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 控制面的最小应用服务。
 * 当前通过仓储接口持久化 session、trace 和 plan，
 * controller 与协议层不直接感知数据库实现。
 */
@Service
public class SessionApplicationService {

    private final Clock clock;
    private final SessionRepository sessionRepository;
    private final TraceEventRepository traceEventRepository;
    private final PlanRepository planRepository;

    public SessionApplicationService(
            Clock clock,
            SessionRepository sessionRepository,
            TraceEventRepository traceEventRepository,
            PlanRepository planRepository
    ) {
        this.clock = clock;
        this.sessionRepository = sessionRepository;
        this.traceEventRepository = traceEventRepository;
        this.planRepository = planRepository;
    }

    @Transactional
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

        // 先持久化 session，确保后续 trace 的外键能归档到同一条主线。
        sessionRepository.save(summary);

        return summary;
    }

    public SessionSummary getSession(String sessionId) {
        return requireSession(sessionId);
    }

    @Transactional
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

        // 先把事件追加到数据库事件流中，确保回放查询能按 sequence_no 稳定排序。
        traceEventRepository.save(record);

        // trace 到来说明会话刚发生过动作，因此把 updatedAt 推进到事件发生时间。
        sessionRepository.updateUpdatedAt(session.sessionId(), record.occurredAt());

        return record;
    }

    public List<TraceEventRecord> listTraceEvents(String sessionId) {
        requireSession(sessionId);
        return traceEventRepository.findBySessionId(sessionId);
    }

    @Transactional
    public PlanRecord createPlan(String sessionId, CreatePlanRequest request) {
        validateCreatePlanRequest(request);
        requireSession(sessionId);

        Instant now = Instant.now(clock);
        PlanRecord plan = new PlanRecord(
                "plan-" + UUID.randomUUID(),
                sessionId,
                normalizeOptionalText(request.turnId()),
                request.content().trim(),
                PlanStatus.PENDING_CONFIRM,
                now,
                now
        );

        // Plan 创建后固定进入待确认状态，不能由客户端直接指定初始状态。
        planRepository.save(plan);
        return plan;
    }

    public PlanRecord getLatestPlan(String sessionId) {
        requireSession(sessionId);
        return planRepository.findLatestBySessionId(sessionId)
                .orElseThrow(() -> new PlanNotFoundException("latest for session " + sessionId));
    }

    @Transactional
    public PlanRecord updatePlanStatus(
            String sessionId,
            String planId,
            UpdatePlanStatusRequest request
    ) {
        validateUpdatePlanStatusRequest(request);
        requireSession(sessionId);
        PlanRecord currentPlan = requirePlan(sessionId, planId);
        assertLegalPlanTransition(currentPlan.status(), request.status());

        Instant now = Instant.now(clock);
        // 状态流转先写数据库，再返回重新构造的事实对象。
        planRepository.updateStatus(sessionId, planId, request.status(), now);
        return new PlanRecord(
                currentPlan.planId(),
                currentPlan.sessionId(),
                currentPlan.turnId(),
                currentPlan.content(),
                request.status(),
                currentPlan.createdAt(),
                now
        );
    }

    private SessionSummary requireSession(String sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
    }

    private PlanRecord requirePlan(String sessionId, String planId) {
        return planRepository.findBySessionIdAndPlanId(sessionId, planId)
                .orElseThrow(() -> new PlanNotFoundException(planId));
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

    private void validateCreatePlanRequest(CreatePlanRequest request) {
        Objects.requireNonNull(request, "CreatePlanRequest must not be null.");
        requireNonBlank(request.content(), "content must not be blank.");
    }

    private void validateUpdatePlanStatusRequest(UpdatePlanStatusRequest request) {
        Objects.requireNonNull(request, "UpdatePlanStatusRequest must not be null.");
        Objects.requireNonNull(request.status(), "status must not be null.");
    }

    private void assertLegalPlanTransition(PlanStatus currentStatus, PlanStatus nextStatus) {
        boolean legalTransition = switch (currentStatus) {
            case PENDING_CONFIRM -> nextStatus == PlanStatus.APPROVED || nextStatus == PlanStatus.REJECTED;
            case APPROVED -> nextStatus == PlanStatus.EXECUTED;
            case REJECTED, EXECUTED -> false;
        };
        if (!legalTransition) {
            throw new IllegalArgumentException(
                    "非法 Plan 状态流转: %s -> %s".formatted(currentStatus, nextStatus)
            );
        }
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
