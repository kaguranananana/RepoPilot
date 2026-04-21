package com.repopilot.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ToolCall;
import java.util.List;
import java.util.Map;
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
                ConversationMessage.assistantToolCalls(List.of(new ToolCall(
                        "call-1",
                        "read_file",
                        Map.of("path", "pom.xml")
                ))),
                ConversationMessage.toolResult("call-1", "[read_file] <project/>"),
                new ConversationMessage(MessageRole.ASSISTANT, "继续读取 docs")
        ), snapshot);

        assertEquals(
                List.of(
                        MessageRole.SYSTEM,
                        MessageRole.WORKING_MEMORY,
                        MessageRole.CONTEXT_SUMMARY,
                        MessageRole.ASSISTANT,
                        MessageRole.TOOL,
                        MessageRole.ASSISTANT
                ),
                result.messages().stream().map(ConversationMessage::role).toList()
        );
        assertEquals(2, result.compactedHighFidelityMessageCount());
        assertEquals("working_memory", result.messages().get(1).content().lines().findFirst().orElseThrow());
        assertEquals("context_summary", result.messages().get(2).content().lines().findFirst().orElseThrow());
    }

    @Test
    void shouldPreserveActivatedSkillSystemMessagesDuringCompaction() {
        ContextCompactionPolicy policy = new ContextCompactionPolicy(3, 1, 1);
        ContextCompactor compactor = new ContextCompactor(policy);
        WorkingMemorySnapshot snapshot = new WorkingMemorySnapshot(
                "修复失败测试",
                List.of("已激活 debug Skill"),
                List.of("activate_skill(name=debug) -> SUCCESS"),
                List.of(),
                List.of(),
                "继续读取失败堆栈",
                List.of("SKILL.md"),
                List.of("activate_skill(name=debug)"),
                List.of(),
                List.of(),
                List.of(),
                1,
                3,
                "compaction-2",
                "high_fidelity_message_limit"
        );

        ContextCompactor.CompactionResult result = compactor.compact(List.of(
                new ConversationMessage(MessageRole.SYSTEM, "system"),
                new ConversationMessage(
                        MessageRole.SYSTEM,
                        "# Activated Skill\nname: debug\nsource: project\n\n## Debug Skill\n先复现，再缩小范围。"
                ),
                new ConversationMessage(MessageRole.USER, "修复失败测试"),
                new ConversationMessage(MessageRole.ASSISTANT, "先读取堆栈"),
                new ConversationMessage(MessageRole.TOOL, "[read_file] stacktrace", "call-1", List.of()),
                new ConversationMessage(MessageRole.ASSISTANT, "继续分析")
        ), snapshot);

        assertTrue(result.messages().stream()
                .filter(message -> message.role() == MessageRole.SYSTEM)
                .anyMatch(message -> message.content().contains("# Activated Skill")));
    }

    @Test
    void shouldRetainAssistantToolCallWhenRecentWindowContainsToolResult() {
        ContextCompactionPolicy policy = new ContextCompactionPolicy(4, 1, 1);
        ContextCompactor compactor = new ContextCompactor(policy);
        WorkingMemorySnapshot snapshot = new WorkingMemorySnapshot(
                "读取多个文件",
                List.of("已读取 notes/a.txt"),
                List.of("read_file(path=notes/a.txt) -> SUCCESS"),
                List.of(),
                List.of(),
                "继续推进当前任务",
                List.of("notes/a.txt"),
                List.of("read_file(path=notes/a.txt)"),
                List.of(),
                List.of(),
                List.of(),
                1,
                4,
                "compaction-1",
                "high_fidelity_message_limit"
        );

        ContextCompactor.CompactionResult result = compactor.compact(List.of(
                new ConversationMessage(MessageRole.USER, "读取多个文件"),
                ConversationMessage.assistantToolCalls(List.of(new ToolCall(
                        "call-a",
                        "read_file",
                        Map.of("path", "notes/a.txt")
                ))),
                ConversationMessage.toolResult("call-a", "[read_file] A"),
                ConversationMessage.assistantToolCalls(List.of(new ToolCall(
                        "call-b",
                        "read_file",
                        Map.of("path", "notes/b.txt")
                ))),
                ConversationMessage.toolResult("call-b", "[read_file] B")
        ), snapshot);

        List<ConversationMessage> retainedMessages = result.messages().stream()
                .filter(message -> message.role() == MessageRole.ASSISTANT || message.role() == MessageRole.TOOL)
                .toList();
        assertEquals(List.of(MessageRole.ASSISTANT, MessageRole.TOOL), retainedMessages.stream()
                .map(ConversationMessage::role)
                .toList());
        assertEquals("call-b", retainedMessages.get(0).toolCalls().get(0).id());
        assertEquals("call-b", retainedMessages.get(1).toolCallId());
    }

    @Test
    void shouldMicrocompactOlderCompactableToolResultsAndKeepLatestRawToolResult() {
        ContextCompactionPolicy policy = new ContextCompactionPolicy(6, 6, 1);
        ContextCompactor compactor = new ContextCompactor(policy);
        WorkingMemorySnapshot snapshot = new WorkingMemorySnapshot(
                "读取多个长文件",
                List.of("已读取 notes/a.txt", "已读取 notes/b.txt"),
                List.of("read_file(path=notes/a.txt) -> SUCCESS", "read_file(path=notes/b.txt) -> SUCCESS"),
                List.of(),
                List.of(),
                "继续分析文件内容",
                List.of("notes/a.txt", "notes/b.txt"),
                List.of("read_file(path=notes/a.txt)", "read_file(path=notes/b.txt)"),
                List.of(),
                List.of(),
                List.of(),
                1,
                0,
                "compaction-1",
                "token_budget"
        );
        String firstLongOutput = "[read_file] " + "a".repeat(2_000);
        String secondLongOutput = "[read_file] " + "b".repeat(2_000);

        ContextCompactor.CompactionResult result = compactor.compact(List.of(
                new ConversationMessage(MessageRole.USER, "读取多个长文件"),
                ConversationMessage.assistantToolCalls(List.of(new ToolCall(
                        "call-a",
                        "read_file",
                        Map.of("path", "notes/a.txt")
                ))),
                ConversationMessage.toolResult("call-a", firstLongOutput),
                ConversationMessage.assistantToolCalls(List.of(new ToolCall(
                        "call-b",
                        "read_file",
                        Map.of("path", "notes/b.txt")
                ))),
                ConversationMessage.toolResult("call-b", secondLongOutput)
        ), snapshot);

        List<ConversationMessage> toolMessages = result.messages().stream()
                .filter(message -> message.role() == MessageRole.TOOL)
                .toList();
        assertEquals(1, result.microcompactedToolResultCount());
        assertTrue(toolMessages.get(0).content().contains("microcompact_tool_result"));
        assertTrue(toolMessages.get(0).content().contains("tool_name: read_file"));
        assertTrue(toolMessages.get(0).content().contains("path=notes/a.txt"));
        assertFalse(toolMessages.get(0).content().contains("a".repeat(200)));
        assertEquals(secondLongOutput, toolMessages.get(1).content());
    }
}
