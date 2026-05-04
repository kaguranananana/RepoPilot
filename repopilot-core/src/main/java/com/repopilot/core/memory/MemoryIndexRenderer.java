package com.repopilot.core.memory;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 负责 `MEMORY.md` 的稳定渲染与解析。
 * 这里故意采用极小 Markdown 子集，避免首版引入通用 Markdown 解析器。
 */
public final class MemoryIndexRenderer {

    private static final String HEADER = "# Memory Index";

    public String render(List<MemoryIndexEntry> entries) {
        Objects.requireNonNull(entries, "entries must not be null.");

        StringBuilder builder = new StringBuilder(HEADER);
        for (MemoryIndexEntry entry : entries) {
            builder.append(System.lineSeparator())
                    .append("- id: ").append(entry.id()).append(System.lineSeparator())
                    .append("  type: ").append(entry.type().storageValue()).append(System.lineSeparator())
                    .append("  title: ").append(entry.title()).append(System.lineSeparator())
                    .append("  summary: ").append(entry.summary()).append(System.lineSeparator())
                    .append("  updated_at: ").append(entry.updatedAt());
        }
        return builder.toString();
    }

    public List<MemoryIndexEntry> parse(String markdown) {
        String safeMarkdown = requireNonBlank(markdown, "markdown must not be blank.");
        List<String> lines = safeMarkdown.lines().toList();
        if (lines.isEmpty() || !HEADER.equals(lines.get(0).strip())) {
            throw new IllegalArgumentException("Memory index 缺少固定头部: " + HEADER);
        }
        if (lines.size() == 1) {
            return List.of();
        }

        List<MemoryIndexEntry> entries = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();

        // 解析时固定按 5 行一组读取。
        // 第一行必须是 `- id:`，
        // 后四行必须是缩进 2 空格的固定字段，
        // 这样可以把首版格式限制在最小可控范围内。
        for (int index = 1; index < lines.size();) {
            if (lines.get(index).isBlank()) {
                index += 1;
                continue;
            }
            ensureEnoughLines(lines, index, 5);

            String id = readValue(lines.get(index), "- id: ");
            String type = readValue(lines.get(index + 1), "  type: ");
            String title = readValue(lines.get(index + 2), "  title: ");
            String summary = readValue(lines.get(index + 3), "  summary: ");
            String updatedAt = readValue(lines.get(index + 4), "  updated_at: ");

            if (!seenIds.add(id)) {
                throw new IllegalArgumentException("检测到重复 memory id: " + id);
            }

            entries.add(new MemoryIndexEntry(
                    id,
                    MemoryType.fromStorageValue(type),
                    title,
                    summary,
                    parseInstant(updatedAt, lines.get(index + 4))
            ));
            index += 5;
        }

        return List.copyOf(entries);
    }

    private void ensureEnoughLines(List<String> lines, int startIndex, int requiredLines) {
        if (startIndex + requiredLines > lines.size()) {
            throw new IllegalArgumentException("Memory index 条目不完整，从第 " + (startIndex + 1) + " 行开始。");
        }
    }

    private String readValue(String line, String prefix) {
        if (!line.startsWith(prefix)) {
            throw new IllegalArgumentException("Memory index 行格式非法: " + line);
        }
        String value = line.substring(prefix.length()).strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Memory index 行缺少值: " + line);
        }
        return value;
    }

    private Instant parseInstant(String value, String rawLine) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Memory index 时间格式非法: " + rawLine, exception);
        }
    }

    private String requireNonBlank(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
