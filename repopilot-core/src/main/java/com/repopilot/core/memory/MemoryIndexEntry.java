package com.repopilot.core.memory;

import java.time.Instant;
import java.util.Objects;

/**
 * `MEMORY.md` 中的轻量索引条目。
 * 它只保留自动召回和列表展示需要的最小字段。
 */
public record MemoryIndexEntry(
        String id,
        MemoryType type,
        String title,
        String summary,
        Instant updatedAt
) {

    public MemoryIndexEntry {
        id = requireNonBlank(id, "id must not be blank.");
        type = Objects.requireNonNull(type, "type must not be null.");
        title = requireNonBlank(title, "title must not be blank.");
        summary = requireNonBlank(summary, "summary must not be blank.");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null.");
    }

    private static String requireNonBlank(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
