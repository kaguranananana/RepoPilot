package com.repopilot.core.trace;

import com.repopilot.protocol.trace.TraceEventType;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 运行时 trace 发布抽象。
 * core 只负责在关键生命周期节点产出结构化事件，
 * 具体要打印到终端、同步到 server，还是写入别的通道，都交给实现方决定。
 */
public interface TracePublisher {

    static TracePublisher noop() {
        return NoopTracePublisher.INSTANCE;
    }

    void publish(TraceEvent event);

    /**
     * 一条最小 trace 事件。
     * 这里只保留任务 9 当前真正需要的四个字段：
     * 1. type：事件类型，决定控制面如何归类。
     * 2. summary：给回放和排障看的稳定摘要。
     * 3. occurredAt：事件发生时间，确保 server 能按真实时间线排序。
     * 4. metadata：补充 step、tool、status 等结构化信息。
     */
    record TraceEvent(
            TraceEventType type,
            String summary,
            Instant occurredAt,
            Map<String, String> metadata
    ) {

        public TraceEvent {
            type = Objects.requireNonNull(type, "type must not be null.");
            if (summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("summary must not be blank.");
            }
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null.");
            // 这里主动把 metadata 复制成不可变结构，
            // 避免发布方在事件创建后继续修改 Map，污染已落地的数据。
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    /**
     * 默认 no-op 发布器。
     * 当调用方暂时不需要 trace 时，主链路依然保持统一调用姿势，
     * 不需要在 AgentLoop 里塞额外的条件分支。
     */
    final class NoopTracePublisher implements TracePublisher {

        private static final NoopTracePublisher INSTANCE = new NoopTracePublisher();

        private NoopTracePublisher() {
        }

        @Override
        public void publish(TraceEvent event) {
            Objects.requireNonNull(event, "event must not be null.");
        }
    }
}
