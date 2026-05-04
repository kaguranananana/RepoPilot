package com.repopilot.core.memory;

import com.repopilot.core.agent.AgentRunMode;
import java.util.List;
import java.util.Objects;

/**
 * 记忆召回选择请求。
 * 它只承载当前任务文本、运行模式和候选索引条目，不直接携带正文。
 */
public record MemoryRecallSelectionRequest(
        String prompt,
        AgentRunMode runMode,
        List<MemoryIndexEntry> candidates
) {

    public MemoryRecallSelectionRequest {
        prompt = requireNonBlank(prompt, "prompt must not be blank.");
        runMode = Objects.requireNonNull(runMode, "runMode must not be null.");
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    private static String requireNonBlank(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
