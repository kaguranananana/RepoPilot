package com.repopilot.core.memory;

import com.repopilot.core.agent.AgentRunMode;
import java.util.List;
import java.util.Objects;

/**
 * 记忆召回服务。
 * 一期只做两件事：空候选短路；非空候选委托给 selector。
 */
public final class MemoryRecallService {

    private final MemoryRecallSelector selector;

    public MemoryRecallService(MemoryRecallSelector selector) {
        this.selector = Objects.requireNonNull(selector, "selector must not be null.");
    }

    public MemoryRecallSelection recall(
            String prompt,
            AgentRunMode runMode,
            List<MemoryIndexEntry> candidates
    ) {
        List<MemoryIndexEntry> safeCandidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (safeCandidates.isEmpty()) {
            return MemoryRecallSelection.empty();
        }
        return selector.select(new MemoryRecallSelectionRequest(prompt, runMode, safeCandidates));
    }
}
