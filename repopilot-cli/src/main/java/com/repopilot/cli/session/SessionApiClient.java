package com.repopilot.cli.session;

import com.repopilot.protocol.session.CreateSessionRequest;
import com.repopilot.protocol.session.SessionSummary;

/**
 * CLI 访问控制面 session API 的抽象接口。
 */
public interface SessionApiClient {

    SessionSummary createSession(CreateSessionRequest request);

    SessionSummary getSession(String sessionId);
}

