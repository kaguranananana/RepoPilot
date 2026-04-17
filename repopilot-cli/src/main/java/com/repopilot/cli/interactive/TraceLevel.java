package com.repopilot.cli.interactive;

import java.util.Locale;

/**
 * 终端 trace 输出级别。
 * SUMMARY 只看关键摘要，
 * VERBOSE 会额外展开消息快照和 tool-calling 明细。
 */
public enum TraceLevel {
    SUMMARY,
    VERBOSE;

    public static TraceLevel fromEnvironmentValue(String value) {
        // 这里把缺省值显式固定为 SUMMARY，
        // 让现有交互行为在不配置时保持稳定，不会突然刷出大量调试信息。
        if (value == null || value.isBlank()) {
            return SUMMARY;
        }

        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "summary" -> SUMMARY;
            case "verbose" -> VERBOSE;
            default -> throw new IllegalArgumentException(
                    "REPOPILOT_TRACE_LEVEL must be one of: summary, verbose."
            );
        };
    }

    public boolean isVerbose() {
        return this == VERBOSE;
    }
}
