package com.repopilot.core.memory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 单条完整持久记忆。
 * 这里的正文承载长期有效事实，不应该混入临时任务过程。
 */
public record MemoryRecord(
        String id,
        MemoryType type,
        String title,
        String summary,
        String body,
        Instant createdAt,
        Instant updatedAt,
        List<String> tags
) {

    public MemoryRecord {
        id = requireNonBlank(id, "id must not be blank.");
        type = Objects.requireNonNull(type, "type must not be null.");
        title = requireNonBlank(title, "title must not be blank.");
        summary = requireNonBlank(summary, "summary must not be blank.");
        body = requireNonBlank(body, "body must not be blank.");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null.");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null.");
        tags = normalizeTags(tags);
    }

    public MemoryIndexEntry toIndexEntry() {
        return new MemoryIndexEntry(id, type, title, summary, updatedAt);
    }

    private static List<String> normalizeTags(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(value -> requireNonBlank(value, "tag must not be blank."))
                .toList();
    }

    private static String requireNonBlank(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
