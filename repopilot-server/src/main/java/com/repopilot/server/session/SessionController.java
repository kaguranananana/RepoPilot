package com.repopilot.server.session;

import com.repopilot.protocol.session.CreateSessionRequest;
import com.repopilot.protocol.session.SessionSummary;
import com.repopilot.protocol.trace.AppendTraceEventRequest;
import com.repopilot.protocol.trace.TraceEventRecord;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话控制器暴露最小控制面 API。
 * 当前先覆盖 session 创建、查询和 trace 记录，
 * 这样 CLI 在下一阶段就有地方可以上报运行过程。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionApplicationService sessionApplicationService;

    public SessionController(SessionApplicationService sessionApplicationService) {
        this.sessionApplicationService = sessionApplicationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionSummary createSession(@RequestBody CreateSessionRequest request) {
        return sessionApplicationService.createSession(request);
    }

    @GetMapping("/{sessionId}")
    public SessionSummary getSession(@PathVariable("sessionId") String sessionId) {
        return sessionApplicationService.getSession(sessionId);
    }

    @PostMapping("/{sessionId}/trace-events")
    @ResponseStatus(HttpStatus.CREATED)
    public TraceEventRecord appendTraceEvent(
            @PathVariable("sessionId") String sessionId,
            @RequestBody AppendTraceEventRequest request
    ) {
        return sessionApplicationService.appendTraceEvent(sessionId, request);
    }

    @GetMapping("/{sessionId}/trace-events")
    public List<TraceEventRecord> listTraceEvents(@PathVariable("sessionId") String sessionId) {
        return sessionApplicationService.listTraceEvents(sessionId);
    }
}
