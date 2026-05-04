package com.repopilot.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryIndexRendererTest {

    @Test
    void shouldRenderAndParseStableMemoryIndex() {
        MemoryIndexRenderer renderer = new MemoryIndexRenderer();
        List<MemoryIndexEntry> entries = List.of(
                new MemoryIndexEntry(
                        "project-plan-execute-boundary",
                        MemoryType.PROJECT,
                        "Plan 与 Execute 必须分阶段",
                        "该仓库要求先只读取证，再进入修改与验证。",
                        Instant.parse("2026-05-04T10:00:00Z")
                ),
                new MemoryIndexEntry(
                        "user-prefer-chinese-comments",
                        MemoryType.USER,
                        "默认使用中文",
                        "回答和代码注释优先使用中文。",
                        Instant.parse("2026-05-04T10:05:00Z")
                )
        );

        String markdown = renderer.render(entries);

        assertTrue(markdown.contains("# Memory Index"));
        assertTrue(markdown.contains("project-plan-execute-boundary"));
        assertTrue(markdown.contains("user-prefer-chinese-comments"));
        assertEquals(entries, renderer.parse(markdown));
    }

    @Test
    void shouldRejectDuplicateMemoryIdsWhenParsingIndex() {
        MemoryIndexRenderer renderer = new MemoryIndexRenderer();
        String markdown = """
                # Memory Index
                - id: duplicated-id
                  type: project
                  title: 标题一
                  summary: 摘要一
                  updated_at: 2026-05-04T10:00:00Z
                - id: duplicated-id
                  type: user
                  title: 标题二
                  summary: 摘要二
                  updated_at: 2026-05-04T10:05:00Z
                """;

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> renderer.parse(markdown)
        );

        assertTrue(exception.getMessage().contains("duplicated-id"));
    }

    @Test
    void shouldRejectInvalidIndexLineFormat() {
        MemoryIndexRenderer renderer = new MemoryIndexRenderer();
        String markdown = """
                # Memory Index
                - id: project-plan-execute-boundary
                  type project
                  title: Plan 与 Execute 必须分阶段
                  summary: 该仓库要求先只读取证，再进入修改与验证。
                  updated_at: 2026-05-04T10:00:00Z
                """;

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> renderer.parse(markdown)
        );

        assertTrue(exception.getMessage().contains("type project"));
    }
}
