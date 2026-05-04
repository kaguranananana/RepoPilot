package com.repopilot.core.memory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 工作区文件版持久记忆存储。
 * 所有文件都限定在 `.repopilot/memory/` 下，避免首版把控制面或数据库耦合进来。
 */
public final class FilePersistentMemoryStore implements PersistentMemoryStore {

    private static final String INDEX_FILE_NAME = "MEMORY.md";

    private final Path memoryRoot;
    private final MemoryIndexRenderer indexRenderer;

    public FilePersistentMemoryStore(Path workspaceRoot) {
        this(workspaceRoot, new MemoryIndexRenderer());
    }

    FilePersistentMemoryStore(Path workspaceRoot, MemoryIndexRenderer indexRenderer) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null.");
        this.memoryRoot = workspaceRoot.toAbsolutePath().normalize().resolve(".repopilot/memory");
        this.indexRenderer = Objects.requireNonNull(indexRenderer, "indexRenderer must not be null.");
    }

    @Override
    public void save(MemoryRecord record) {
        Objects.requireNonNull(record, "record must not be null.");

        Path recordPath = resolveRecordPath(record.id(), record.type());
        try {
            Files.createDirectories(recordPath.getParent());
            Files.writeString(recordPath, renderRecord(record), StandardCharsets.UTF_8);
            rewriteIndex(scanAllRecords());
        } catch (IOException exception) {
            throw new UncheckedIOException("持久记忆写入失败: " + record.id(), exception);
        }
    }

    @Override
    public Optional<MemoryRecord> get(String id) {
        String safeId = requireSafeId(id);
        for (Path recordFile : scanRecordFiles()) {
            MemoryRecord record = readRecord(recordFile);
            if (record.id().equals(safeId)) {
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<MemoryIndexEntry> list() {
        return scanAllRecords().stream()
                .map(MemoryRecord::toIndexEntry)
                .toList();
    }

    @Override
    public void delete(String id) {
        Optional<MemoryRecord> record = get(id);
        if (record.isEmpty()) {
            return;
        }

        Path recordPath = resolveRecordPath(record.get().id(), record.get().type());
        try {
            Files.deleteIfExists(recordPath);
            rewriteIndex(scanAllRecords());
        } catch (IOException exception) {
            throw new UncheckedIOException("持久记忆删除失败: " + id, exception);
        }
    }

    private List<MemoryRecord> scanAllRecords() {
        List<MemoryRecord> records = new ArrayList<>();
        for (Path recordFile : scanRecordFiles()) {
            records.add(readRecord(recordFile));
        }
        return List.copyOf(records);
    }

    private List<Path> scanRecordFiles() {
        if (!Files.exists(memoryRoot)) {
            return List.of();
        }

        try (Stream<Path> pathStream = Files.find(
                memoryRoot,
                Integer.MAX_VALUE,
                (path, attributes) -> attributes.isRegularFile()
                        && path.getFileName().toString().endsWith(".md")
                        && !path.getFileName().toString().equals(INDEX_FILE_NAME)
        )) {
            return pathStream.sorted().toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("持久记忆扫描失败: " + memoryRoot, exception);
        }
    }

    private MemoryRecord readRecord(Path recordPath) {
        try (BufferedReader reader = Files.newBufferedReader(recordPath, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            if (firstLine == null || !firstLine.strip().equals("---")) {
                throw new IllegalArgumentException("Memory record 缺少 front matter: " + recordPath);
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            String currentListKey = null;
            List<String> bodyLines = new ArrayList<>();
            boolean frontMatterClosed = false;

            // 先逐行读取 front matter。
            // 遇到第二个 `---` 之前，只接受固定的 `key: value` 或 `tags` 列表项。
            // 一旦读到结束分隔符，就立刻跳出这一段，
            // 后面的所有行都按正文原样收集，
            // 最后统一组装成 `MemoryRecord` 返回。
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    throw new IllegalArgumentException("Memory record front matter 未正确闭合: " + recordPath);
                }
                if (line.strip().equals("---")) {
                    frontMatterClosed = true;
                    break;
                }

                String strippedLine = line.strip();
                if (strippedLine.isEmpty()) {
                    continue;
                }
                if (strippedLine.startsWith("- ")) {
                    if (!"tags".equals(currentListKey)) {
                        throw new IllegalArgumentException("Memory record 列表项缺少 tags 键: " + recordPath);
                    }
                    @SuppressWarnings("unchecked")
                    List<String> tags = (List<String>) metadata.get("tags");
                    tags.add(requireNonBlank(strippedLine.substring(2), "Memory record tag 不能为空: " + recordPath));
                    continue;
                }

                int separatorIndex = strippedLine.indexOf(':');
                if (separatorIndex <= 0) {
                    throw new IllegalArgumentException("Memory record front matter 行格式非法: " + line);
                }
                String key = strippedLine.substring(0, separatorIndex).strip();
                String value = strippedLine.substring(separatorIndex + 1).strip();
                currentListKey = null;

                if (metadata.containsKey(key)) {
                    throw new IllegalArgumentException("Memory record front matter 重复键: " + key);
                }
                if (value.isEmpty()) {
                    if ("tags".equals(key)) {
                        List<String> tags = new ArrayList<>();
                        metadata.put(key, tags);
                        currentListKey = key;
                        continue;
                    }
                    throw new IllegalArgumentException("Memory record front matter 缺少值: " + key);
                }
                metadata.put(key, value);
            }
            if (!frontMatterClosed) {
                throw new IllegalArgumentException("Memory record front matter 未正确闭合: " + recordPath);
            }

            String bodyLine;
            while ((bodyLine = reader.readLine()) != null) {
                bodyLines.add(bodyLine);
            }
            return buildRecord(recordPath, metadata, bodyLines);
        } catch (IOException exception) {
            throw new UncheckedIOException("持久记忆读取失败: " + recordPath, exception);
        }
    }

    private void rewriteIndex(List<MemoryRecord> records) throws IOException {
        Files.createDirectories(memoryRoot);
        List<MemoryIndexEntry> entries = records.stream()
                .map(MemoryRecord::toIndexEntry)
                .toList();
        Files.writeString(
                memoryRoot.resolve(INDEX_FILE_NAME),
                indexRenderer.render(entries),
                StandardCharsets.UTF_8
        );
    }

    private String renderRecord(MemoryRecord record) {
        StringBuilder builder = new StringBuilder();
        builder.append("---").append(System.lineSeparator());
        builder.append("id: ").append(record.id()).append(System.lineSeparator());
        builder.append("type: ").append(record.type().storageValue()).append(System.lineSeparator());
        builder.append("title: ").append(record.title()).append(System.lineSeparator());
        builder.append("summary: ").append(record.summary()).append(System.lineSeparator());
        builder.append("created_at: ").append(record.createdAt()).append(System.lineSeparator());
        builder.append("updated_at: ").append(record.updatedAt()).append(System.lineSeparator());
        builder.append("tags:");
        for (String tag : record.tags()) {
            builder.append(System.lineSeparator()).append("  - ").append(tag);
        }
        builder.append(System.lineSeparator())
                .append("---")
                .append(System.lineSeparator())
                .append(System.lineSeparator())
                .append(record.body());
        return builder.toString();
    }

    private Path resolveRecordPath(String id, MemoryType type) {
        String safeId = requireSafeId(id);
        Path recordPath = memoryRoot
                .resolve(type.storageValue())
                .resolve(safeId + ".md")
                .normalize();
        if (!recordPath.startsWith(memoryRoot)) {
            throw new IllegalArgumentException("Memory record path 越界: " + id);
        }
        return recordPath;
    }

    private String requireSafeId(String id) {
        String safeId = requireNonBlank(id, "memory id must not be blank.");
        // 这里先拒绝 `..`、斜杠和反斜杠。
        // 这样 id 永远只是一个文件名片段，
        // 不会被拼接成跨目录路径，
        // 后续再和 normalize + startsWith 双重校验配合形成硬边界。
        if (safeId.contains("..") || safeId.contains("/") || safeId.contains("\\")) {
            throw new IllegalArgumentException("Memory record path 越界: " + id);
        }
        return safeId;
    }

    private MemoryRecord buildRecord(Path recordPath, Map<String, Object> metadata, List<String> bodyLines) {
        String body = joinBodyLines(bodyLines);
        return new MemoryRecord(
                readRequiredText(metadata, "id", recordPath),
                MemoryType.fromStorageValue(readRequiredText(metadata, "type", recordPath)),
                readRequiredText(metadata, "title", recordPath),
                readRequiredText(metadata, "summary", recordPath),
                requireNonBlank(body, "Memory record body 不能为空: " + recordPath),
                parseInstant(readRequiredText(metadata, "created_at", recordPath), "created_at", recordPath),
                parseInstant(readRequiredText(metadata, "updated_at", recordPath), "updated_at", recordPath),
                castTags(metadata.get("tags"), recordPath)
        );
    }

    private String joinBodyLines(List<String> bodyLines) {
        int startIndex = 0;
        if (!bodyLines.isEmpty() && bodyLines.get(0).isBlank()) {
            startIndex = 1;
        }
        return String.join(System.lineSeparator(), bodyLines.subList(startIndex, bodyLines.size())).stripTrailing();
    }

    private String readRequiredText(Map<String, Object> metadata, String key, Path recordPath) {
        Object rawValue = metadata.get(key);
        if (!(rawValue instanceof String stringValue) || stringValue.isBlank()) {
            throw new IllegalArgumentException("Memory record 缺少必填字段: " + key + " (" + recordPath + ")");
        }
        return stringValue.strip();
    }

    private Instant parseInstant(String value, String fieldName, Path recordPath) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Memory record 时间字段格式非法: " + fieldName + " (" + recordPath + ")", exception);
        }
    }

    private List<String> castTags(Object value, Path recordPath) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> rawTags)) {
            throw new IllegalArgumentException("Memory record tags 类型非法: " + recordPath);
        }
        List<String> tags = new ArrayList<>(rawTags.size());
        for (Object rawTag : rawTags) {
            if (!(rawTag instanceof String stringTag)) {
                throw new IllegalArgumentException("Memory record tag 必须是字符串: " + recordPath);
            }
            tags.add(requireNonBlank(stringTag, "Memory record tag 不能为空: " + recordPath));
        }
        return List.copyOf(tags);
    }

    private String requireNonBlank(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
