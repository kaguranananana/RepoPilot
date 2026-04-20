package com.repopilot.protocol.trace;

/**
 * trace 事件类型枚举。
 * 这里先覆盖一期控制面一定会看到的关键事件，
 * 后续再扩展 task、diff、approval 等更细的类型。
 */
public enum TraceEventType {
    SESSION_CREATED,
    USER_MESSAGE_RECEIVED,
    MODEL_CALL_REQUESTED,
    MODEL_RESPONSE_RECEIVED,
    TOOL_CALL_REQUESTED,
    TOOL_CALL_COMPLETED,
    // 连续重复同一工具调用达到阈值时的显式中断事件。
    TOOL_CALL_LOOP_DETECTED,
    CONTEXT_COMPACTION_STARTED,
    CONTEXT_COMPACTION_COMPLETED,
    APPROVAL_REQUESTED,
    APPROVAL_RECORDED,
    DIFF_REVIEW_GENERATED,
    SESSION_STATUS_CHANGED
}
