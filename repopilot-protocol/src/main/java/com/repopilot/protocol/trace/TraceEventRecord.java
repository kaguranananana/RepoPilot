package com.repopilot.protocol.trace;

import java.time.Instant;
import java.util.Map;

/**
 * 控制面持久化后的 trace 事件记录。
 * 与追加请求相比，它多了 traceId 和 sessionId，
 * 这样回放时就能稳定定位“哪次会话里的哪一步动作”。
 */
public record TraceEventRecord(
        String traceId,
        String sessionId,
        TraceEventType type,
        String source,
        String summary,
        Instant occurredAt,
        Map<String, String> metadata
) {

    public TraceEventRecord {
        // 与请求模型保持一致，进入系统后的 metadata 也必须是不可变结构。
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

