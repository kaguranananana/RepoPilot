package com.repopilot.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class StructuredContextSummaryTest {

    @Test
    void shouldPreserveStableSnapshotStateWhenConvertingToWorkingMemorySnapshot() {
        WorkingMemorySnapshot previousSnapshot = new WorkingMemorySnapshot(
                "修复压缩问题",
                List.of("已读取 README.md"),
                List.of("read_file(path=README.md) -> SUCCESS"),
                List.of("run_command(command=mvn test) -> exit 1"),
                List.of("report.md"),
                "继续推进当前任务",
                List.of("README.md"),
                List.of("read_file(path=README.md)"),
                List.of("run_command(command=mvn test) -> exit 1"),
                List.of("不要修改无关文件"),
                List.of("已生成初版报告"),
                3,
                8,
                "compaction-3",
                "token_budget"
        );
        StructuredContextSummary summary = new StructuredContextSummary(
                "修复压缩问题",
                "EXECUTE",
                "已完成摘要替换",
                List.of("ContextCompactor.java"),
                List.of("结构化摘要已生成"),
                List.of("run_command(command=mvn test) -> exit 1"),
                List.of("保留最近高保真窗口"),
                List.of("继续补测试并复测")
        );

        WorkingMemorySnapshot result = summary.toWorkingMemorySnapshot(previousSnapshot);

        assertEquals(
                List.of("已读取 README.md", "结构化摘要已生成"),
                result.confirmedFacts()
        );
        assertEquals(List.of("read_file(path=README.md) -> SUCCESS"), result.recentToolResults());
        assertEquals(List.of("run_command(command=mvn test) -> exit 1"), result.currentBlockers());
        assertEquals(List.of("README.md", "ContextCompactor.java"), result.keyFilesRead());
        assertEquals(List.of("read_file(path=README.md)"), result.importantToolCalls());
        assertEquals(List.of("不要修改无关文件"), result.userConstraints());
        assertEquals(List.of("已生成初版报告"), result.confirmedOutcomes());
        assertEquals("继续补测试并复测", result.nextAction());
        assertEquals(3, result.compactionCount());
        assertEquals(8, result.archivedMessageCount());
        assertEquals("compaction-3", result.resumeCheckpointId());
        assertEquals("token_budget", result.latestArchiveReason());
    }
}
