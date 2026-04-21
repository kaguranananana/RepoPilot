package com.repopilot.server.session;

import com.repopilot.protocol.trace.TraceEventRecord;
import java.util.List;

/**
 * Trace 事件持久化边界。
 * Trace 是 append-only 事件流，查询时按数据库生成的 sequence_no 稳定回放。
 */
public interface TraceEventRepository {

    void save(TraceEventRecord record);

    List<TraceEventRecord> findBySessionId(String sessionId);
}
