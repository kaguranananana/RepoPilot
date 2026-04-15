package com.repopilot.protocol.events;

import java.time.Instant;

/**
 * 协议层会话事件的最小抽象。
 * 这里先只约束所有事件都必须带上会话标识和事件时间，
 * 后续新增 task、trace、approval 事件时都沿用这条基线。
 */
public interface SessionEvent {

    String sessionId();

    Instant occurredAt();
}

