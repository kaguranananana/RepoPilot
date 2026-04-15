package com.repopilot.protocol.session;

/**
 * 创建会话请求。
 * CLI 在真正启动本地 runtime 前，
 * 会先把工作区和请求来源登记到 server，形成一个可审计的 session。
 */
public record CreateSessionRequest(
        String workspaceId,
        String requestedBy
) {
}

