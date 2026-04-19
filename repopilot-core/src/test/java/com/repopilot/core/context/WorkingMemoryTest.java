package com.repopilot.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.tool.ToolExecutionResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkingMemoryTest {

    @Test
    void shouldBuildStructuredSnapshotFromTaskAndToolActivity() {
        ContextCompactionPolicy policy = new ContextCompactionPolicy(6, 3, 2);
        WorkingMemory workingMemory = WorkingMemory.initialize(List.of(
                new ConversationMessage(MessageRole.USER, "完成 task11"),
                new ConversationMessage(MessageRole.USER, "不要修改无关文件")
        ), policy);

        ToolCall readFileCall = new ToolCall("call-1", "read_file", Map.of("path", "pom.xml"));
        workingMemory.recordToolCall(readFileCall);
        workingMemory.recordToolResult(readFileCall, ToolExecutionResult.success("<project/>"));

        ToolCall writeFileCall = new ToolCall("call-2", "write_file", Map.of("path", "docs/output.txt"));
        workingMemory.recordToolCall(writeFileCall);
        workingMemory.recordToolResult(writeFileCall, ToolExecutionResult.success("写入成功"));

        ToolCall runCommandCall = new ToolCall("call-3", "run_command", Map.of("command", "mvn test"));
        workingMemory.recordToolCall(runCommandCall);
        workingMemory.recordToolResult(runCommandCall, ToolExecutionResult.recoverableError("exit code 1"));

        workingMemory.recordCompaction("high_fidelity_message_limit", 4);
        WorkingMemorySnapshot snapshot = workingMemory.snapshot();

        assertEquals("完成 task11", snapshot.taskGoal());
        assertIterableEquals(List.of("不要修改无关文件"), snapshot.userConstraints());
        assertTrue(snapshot.confirmedFacts().contains("已读取文件: pom.xml"));
        assertIterableEquals(
                List.of(
                        "write_file(path=docs/output.txt) -> SUCCESS: 写入成功",
                        "run_command(command=mvn test) -> RECOVERABLE_ERROR: exit code 1"
                ),
                snapshot.recentToolResults()
        );
        assertIterableEquals(List.of("run_command(command=mvn test) -> exit code 1"), snapshot.currentBlockers());
        assertIterableEquals(List.of("docs/output.txt"), snapshot.artifactReferences());
        assertEquals("处理阻塞并继续当前任务: run_command", snapshot.nextAction());

        assertTrue(snapshot.keyFilesRead().contains("pom.xml"));
        assertTrue(snapshot.importantToolCalls().contains("read_file(path=pom.xml)"));
        assertTrue(snapshot.toolErrors().contains("run_command(command=mvn test) -> exit code 1"));
        assertTrue(snapshot.confirmedOutcomes().contains("已写入产出物: docs/output.txt"));

        assertEquals(1, snapshot.compactionCount());
        assertEquals(4, snapshot.archivedMessageCount());
        assertEquals("compaction-1", snapshot.resumeCheckpointId());
        assertEquals("high_fidelity_message_limit", snapshot.latestArchiveReason());
    }

    @Test
    void shouldRestoreSnapshotForFutureSessionRecovery() {
        ContextCompactionPolicy policy = new ContextCompactionPolicy(6, 3, 2);
        WorkingMemorySnapshot snapshot = new WorkingMemorySnapshot(
                "恢复 task11",
                List.of("pom.xml 存在"),
                List.of("read_file(path=pom.xml) -> SUCCESS: <project/>"),
                List.of(),
                List.of("docs/output.txt"),
                "继续生成最终答复",
                List.of("pom.xml"),
                List.of("read_file(path=pom.xml)"),
                List.of(),
                List.of("不要修改无关文件"),
                List.of("已写入产出物: docs/output.txt"),
                2,
                8,
                "compaction-2",
                "manual_rehydrate"
        );

        WorkingMemory restored = WorkingMemory.restore(snapshot, policy);

        assertSame(snapshot, restored.snapshot());
    }
}
