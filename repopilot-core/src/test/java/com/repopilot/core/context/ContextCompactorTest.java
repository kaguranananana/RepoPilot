package com.repopilot.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextCompactorTest {

    @Test
    void shouldKeepSystemMessagesAndRecentHighFidelityMessagesWhenCompacting() {
        ContextCompactionPolicy policy = new ContextCompactionPolicy(3, 2, 2);
        ContextCompactor compactor = new ContextCompactor(policy);
        WorkingMemorySnapshot snapshot = new WorkingMemorySnapshot(
                "完成 task11",
                List.of("已读取 pom.xml"),
                List.of("read_file(path=pom.xml) -> SUCCESS: <project/>"),
                List.of(),
                List.of(),
                "继续调用下一步工具",
                List.of("pom.xml"),
                List.of("read_file(path=pom.xml)"),
                List.of(),
                List.of("不要修改无关文件"),
                List.of(),
                1,
                2,
                "compaction-1",
                "high_fidelity_message_limit"
        );

        ContextCompactor.CompactionResult result = compactor.compact(List.of(
                new ConversationMessage(MessageRole.SYSTEM, "system"),
                new ConversationMessage(MessageRole.USER, "完成 task11"),
                new ConversationMessage(MessageRole.ASSISTANT, "先读取 pom.xml"),
                new ConversationMessage(MessageRole.TOOL, "[read_file] <project/>", "call-1", List.of()),
                new ConversationMessage(MessageRole.ASSISTANT, "继续读取 docs")
        ), snapshot);

        assertEquals(
                List.of(
                        MessageRole.SYSTEM,
                        MessageRole.WORKING_MEMORY,
                        MessageRole.CONTEXT_SUMMARY,
                        MessageRole.TOOL,
                        MessageRole.ASSISTANT
                ),
                result.messages().stream().map(ConversationMessage::role).toList()
        );
        assertEquals(2, result.compactedHighFidelityMessageCount());
        assertEquals("working_memory", result.messages().get(1).content().lines().findFirst().orElseThrow());
        assertEquals("context_summary", result.messages().get(2).content().lines().findFirst().orElseThrow());
    }
}
