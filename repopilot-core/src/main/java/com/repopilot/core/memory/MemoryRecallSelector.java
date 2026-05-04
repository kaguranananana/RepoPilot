package com.repopilot.core.memory;

/**
 * 记忆候选选择器。
 * 它的职责只是从候选索引里选 id，不负责读正文或构建 prompt。
 */
@FunctionalInterface
public interface MemoryRecallSelector {

    MemoryRecallSelection select(MemoryRecallSelectionRequest request);
}
