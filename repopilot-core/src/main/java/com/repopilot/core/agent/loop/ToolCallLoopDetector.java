package com.repopilot.core.agent.loop;

import com.repopilot.core.model.ToolCall;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 连续重复工具调用检测器。
 * 它只比较 `toolName + canonicalArguments`，不推断模型意图。
 */
public final class ToolCallLoopDetector {

    private final int repeatThreshold;
    private String previousToolCallKey;
    private int currentRepeatCount;

    public ToolCallLoopDetector(int repeatThreshold) {
        if (repeatThreshold < 2) {
            throw new IllegalArgumentException("repeatThreshold must be greater than one.");
        }
        this.repeatThreshold = repeatThreshold;
    }

    public ToolCallLoopDetectionResult record(ToolCall toolCall) {
        Objects.requireNonNull(toolCall, "toolCall must not be null.");

        // 先生成稳定 key，确保参数原始顺序不会影响重复判断。
        String currentToolCallKey = canonicalKey(toolCall);
        // 当前 key 等于上一条 key，才算连续重复。
        if (currentToolCallKey.equals(previousToolCallKey)) {
            currentRepeatCount += 1;
        } else {
            // 一旦出现不同 key，就从新的工具调用重新开始计数。
            previousToolCallKey = currentToolCallKey;
            currentRepeatCount = 1;
        }

        // 阈值命中是一个纯数字判断，不引入模型语义或额外启发式。
        boolean loopDetected = currentRepeatCount >= repeatThreshold;
        // 返回完整检测状态，让 AgentLoop 同一份事实同时用于 trace 和异常。
        return new ToolCallLoopDetectionResult(
                loopDetected,
                toolCall.toolName(),
                currentToolCallKey,
                currentRepeatCount,
                canonicalArgumentsSummary(toolCall.arguments())
        );
    }

    public static String canonicalKey(ToolCall toolCall) {
        Objects.requireNonNull(toolCall, "toolCall must not be null.");
        return toolCall.toolName() + "|" + canonicalArguments(toolCall.arguments());
    }

    public static String canonicalArgumentsSummary(Map<String, String> arguments) {
        String canonicalArguments = canonicalArguments(arguments);
        if (canonicalArguments.isEmpty()) {
            return "(none)";
        }
        return canonicalArguments;
    }

    private static String canonicalArguments(Map<String, String> arguments) {
        Objects.requireNonNull(arguments, "arguments must not be null.");
        return arguments.entrySet().stream()
                // 先按参数名排序，这是 canonicalArguments 稳定性的核心。
                .sorted(Map.Entry.comparingByKey())
                // 再对 key/value 做固定转义，避免分隔符进入 key 后造成歧义。
                .map(entry -> escape(entry.getKey()) + "=" + escape(entry.getValue()))
                // 最后用固定分隔符拼接，得到完全可复现的参数串。
                .collect(Collectors.joining("|"));
    }

    private static String escape(String value) {
        Objects.requireNonNull(value, "argument key/value must not be null.");
        return value
                // 反斜杠必须先转义，避免后续新增的转义符被再次解释。
                .replace("\\", "\\\\")
                // 竖线是参数之间的分隔符，进入参数内容时必须显式转义。
                .replace("|", "\\|")
                // 等号是参数名和值之间的分隔符，进入参数内容时必须显式转义。
                .replace("=", "\\=");
    }
}
