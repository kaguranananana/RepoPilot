package com.repopilot.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilePersistentMemoryStoreTest {

    @TempDir
    Path tempRoot;

    @Test
    void shouldSaveMemoryWriteRecordFileAndRewriteIndex() throws Exception {
        Path workspaceRoot = tempRoot.resolve("workspace");
        FilePersistentMemoryStore store = new FilePersistentMemoryStore(workspaceRoot);
        MemoryRecord record = new MemoryRecord(
                "project-plan-execute-boundary",
                MemoryType.PROJECT,
                "Plan 与 Execute 必须分阶段",
                "该仓库要求先只读取证，再进入修改与验证。",
                "在 RepoPilot 中，PLAN 阶段只允许只读工具，EXECUTE 阶段才允许修改与验证。",
                Instant.parse("2026-05-04T10:00:00Z"),
                Instant.parse("2026-05-04T10:00:00Z"),
                List.of("workflow", "runtime")
        );

        store.save(record);

        Path memoryRoot = workspaceRoot.resolve(".repopilot/memory");
        assertEquals(record, store.get("project-plan-execute-boundary").orElseThrow());
        assertEquals(
                List.of(new MemoryIndexEntry(
                        "project-plan-execute-boundary",
                        MemoryType.PROJECT,
                        "Plan 与 Execute 必须分阶段",
                        "该仓库要求先只读取证，再进入修改与验证。",
                        Instant.parse("2026-05-04T10:00:00Z")
                )),
                store.list()
        );
        assertTrue(Files.exists(memoryRoot.resolve("project/project-plan-execute-boundary.md")));
        assertTrue(Files.readString(memoryRoot.resolve("MEMORY.md")).contains("project-plan-execute-boundary"));
    }

    @Test
    void shouldDeleteMemoryRemoveRecordFileAndRewriteIndex() throws Exception {
        Path workspaceRoot = tempRoot.resolve("workspace");
        FilePersistentMemoryStore store = new FilePersistentMemoryStore(workspaceRoot);
        MemoryRecord record = new MemoryRecord(
                "feedback-dont-hide-real-errors",
                MemoryType.FEEDBACK,
                "不要掩盖真实错误",
                "遇到错误要直接暴露，不做静默兜底。",
                "RepoPilot 当前阶段优先暴露真实错误，不要用 fallback 掩盖失败。",
                Instant.parse("2026-05-04T10:00:00Z"),
                Instant.parse("2026-05-04T10:00:00Z"),
                List.of("error-handling")
        );
        Path recordPath = workspaceRoot.resolve(".repopilot/memory/feedback/feedback-dont-hide-real-errors.md");

        store.save(record);
        store.delete("feedback-dont-hide-real-errors");

        assertTrue(store.get("feedback-dont-hide-real-errors").isEmpty());
        assertEquals(List.of(), store.list());
        assertFalse(Files.exists(recordPath));
        assertTrue(Files.readString(workspaceRoot.resolve(".repopilot/memory/MEMORY.md")).contains("# Memory Index"));
    }

    @Test
    void shouldRejectPathTraversalIdWhenSavingMemory() {
        Path workspaceRoot = tempRoot.resolve("workspace");
        FilePersistentMemoryStore store = new FilePersistentMemoryStore(workspaceRoot);
        MemoryRecord invalidRecord = new MemoryRecord(
                "../escape",
                MemoryType.PROJECT,
                "非法 id",
                "不允许路径穿越。",
                "这条记忆的 id 试图跳出 memory 根目录。",
                Instant.parse("2026-05-04T10:00:00Z"),
                Instant.parse("2026-05-04T10:00:00Z"),
                List.of()
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> store.save(invalidRecord)
        );

        assertTrue(exception.getMessage().contains("越界"));
    }

    @Test
    void shouldRejectMemoryFileMissingRequiredFrontMatterField() throws Exception {
        Path workspaceRoot = tempRoot.resolve("workspace");
        Path recordPath = workspaceRoot.resolve(".repopilot/memory/project/project-plan-execute-boundary.md");
        Files.createDirectories(recordPath.getParent());
        Files.writeString(
                recordPath,
                """
                        ---
                        id: project-plan-execute-boundary
                        type: project
                        title: Plan 与 Execute 必须分阶段
                        created_at: 2026-05-04T10:00:00Z
                        updated_at: 2026-05-04T10:00:00Z
                        tags:
                          - workflow
                        ---

                        正文
                        """
        );

        FilePersistentMemoryStore store = new FilePersistentMemoryStore(workspaceRoot);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> store.get("project-plan-execute-boundary")
        );

        assertTrue(exception.getMessage().contains("summary"));
    }
}
