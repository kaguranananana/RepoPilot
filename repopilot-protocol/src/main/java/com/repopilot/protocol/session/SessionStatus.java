package com.repopilot.protocol.session;

/**
 * 会话状态枚举。
 * 一期先保留最核心的生命周期状态，
 * 后续再根据任务系统和审批流补充更细粒度的状态迁移。
 */
public enum SessionStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

