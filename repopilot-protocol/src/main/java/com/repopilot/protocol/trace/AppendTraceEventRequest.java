package com.repopilot.protocol.trace;

import java.time.Instant;
import java.util.Map;

/**
 * 追加 trace 事件的请求模型。
 * metadata 先用扁平字符串字典承载补充信息，
 * 保持协议简单，避免阶段 0 过早引入复杂嵌套结构。
 */
public record AppendTraceEventRequest(
        TraceEventType type,
        String source,
        String summary,
        Instant occurredAt,
        Map<String, String> metadata
) {

    public AppendTraceEventRequest {
        // 这里主动把 metadata 复制为不可变结构，
        // 避免 controller/service 持有调用方可变 Map，污染后续 trace 数据。
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

