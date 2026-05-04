package com.repopilot.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.repopilot.core.agent.AgentRunMode;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelMemoryRecallSelectorTest {

    @Test
    void shouldParseStructuredJsonSelectionFromFinalModelResponse() {
        ModelMemoryRecallSelector selector = new ModelMemoryRecallSelector(
                messages -> new FinalModelResponse("""
                        {
                          "selected_ids": ["project-plan-execute-boundary"]
                        }
                        """)
        );

        MemoryRecallSelection selection = selector.select(request());

        assertEquals(List.of("project-plan-execute-boundary"), selection.selectedIds());
    }

    @Test
    void shouldRejectToolCallsFromRecallSelectorModel() {
        ModelMemoryRecallSelector selector = new ModelMemoryRecallSelector(
                new SingleResponseModelAdapter(new ToolCallModelResponse(List.of(new ToolCall(
                        "call-1",
                        "read_file",
                        Map.of("path", "README.md")
                ))))
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> selector.select(request())
        );

        assertEquals("记忆召回 selector 不能调用工具。", exception.getMessage());
    }

    @Test
    void shouldRejectUnknownMemoryIdsReturnedByModel() {
        ModelMemoryRecallSelector selector = new ModelMemoryRecallSelector(
                messages -> new FinalModelResponse("""
                        {
                          "selected_ids": ["unknown-id"]
                        }
                        """)
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> selector.select(request())
        );

        assertEquals("记忆召回 selector 返回了未知 id: unknown-id", exception.getMessage());
    }

    @Test
    void shouldRejectMoreThanThreeSelectedIds() {
        ModelMemoryRecallSelector selector = new ModelMemoryRecallSelector(
                messages -> new FinalModelResponse("""
                        {
                          "selected_ids": ["one", "two", "three", "four"]
                        }
                        """)
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> selector.select(new MemoryRecallSelectionRequest(
                        "分析当前任务",
                        AgentRunMode.EXECUTE,
                        List.of(
                                new MemoryIndexEntry("one", MemoryType.PROJECT, "1", "1", Instant.parse("2026-05-04T10:00:00Z")),
                                new MemoryIndexEntry("two", MemoryType.PROJECT, "2", "2", Instant.parse("2026-05-04T10:00:00Z")),
                                new MemoryIndexEntry("three", MemoryType.PROJECT, "3", "3", Instant.parse("2026-05-04T10:00:00Z")),
                                new MemoryIndexEntry("four", MemoryType.PROJECT, "4", "4", Instant.parse("2026-05-04T10:00:00Z"))
                        )
                ))
        );

        assertEquals("记忆召回 selector 最多只能返回 3 条 id。", exception.getMessage());
    }

    private MemoryRecallSelectionRequest request() {
        return new MemoryRecallSelectionRequest(
                "先分析修改方案",
                AgentRunMode.PLAN,
                List.of(new MemoryIndexEntry(
                        "project-plan-execute-boundary",
                        MemoryType.PROJECT,
                        "Plan 与 Execute 必须分阶段",
                        "该仓库要求先只读取证，再进入修改与验证。",
                        Instant.parse("2026-05-04T10:00:00Z")
                ))
        );
    }

    private static final class SingleResponseModelAdapter implements ModelAdapter {

        private final ModelResponse response;

        private SingleResponseModelAdapter(ModelResponse response) {
            this.response = response;
        }

        @Override
        public ModelResponse next(List<ConversationMessage> messages) {
            return response;
        }
    }
}
