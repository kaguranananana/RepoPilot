package com.repopilot.cli.session;

import com.repopilot.protocol.trace.AppendTraceEventRequest;
import com.repopilot.protocol.trace.TraceEventRecord;

/**
 * CLI 访问控制面 trace API 的抽象接口。
 * 它只负责把运行时产生的结构化 trace 追加到指定 session，
 * 不参与 trace 的生成逻辑。
 */
public interface TraceApiClient {

    TraceEventRecord appendTraceEvent(String sessionId, AppendTraceEventRequest request);
}
