package com.repopilot.core.memory;

import java.util.List;

/**
 * 记忆召回选择结果。
 * 一期只返回 id 列表，真正正文读取放到上层编排器中完成。
 */
public record MemoryRecallSelection(List<String> selectedIds) {

    public MemoryRecallSelection {
        selectedIds = selectedIds == null ? List.of() : List.copyOf(selectedIds);
    }

    public static MemoryRecallSelection empty() {
        return new MemoryRecallSelection(List.of());
    }
}
