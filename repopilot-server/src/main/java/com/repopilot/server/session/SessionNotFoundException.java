package com.repopilot.server.session;

/**
 * 会话不存在时抛出的领域异常。
 * controller advice 会把它稳定映射为 404，
 * 避免把“资源不存在”误报成 500。
 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String sessionId) {
        super("Session not found: " + sessionId);
    }
}

