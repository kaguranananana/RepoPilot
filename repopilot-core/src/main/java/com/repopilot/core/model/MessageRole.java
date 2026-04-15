package com.repopilot.core.model;

/**
 * 对话消息角色枚举。
 * 一期先保留最基础的四类角色，
 * 足够支撑 `user -> tool -> assistant` 的最小闭环。
 */
public enum MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}

