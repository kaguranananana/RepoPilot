package com.repopilot.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecalledMemoryPromptRendererTest {

    private final RecalledMemoryPromptRenderer renderer = new RecalledMemoryPromptRenderer();

    @Test
    void shouldRenderSingleSystemMessageForRecalledMemories() {
        ConversationMessage message = renderer.render(List.of(new MemoryRecord(
                "project-plan-boundary",
                MemoryType.PROJECT,
                "Plan boundary",
                "先分析再修改",
                "PLAN 阶段只允许只读工具。",
                Instant.parse("2026-05-04T10:00:00Z"),
                Instant.parse("2026-05-04T10:05:00Z"),
                List.of("runtime")
        )));

        assertEquals(MessageRole.SYSTEM, message.role());
        assertTrue(message.content().contains("# Recalled Memories"));
        assertTrue(message.content().contains("project-plan-boundary"));
        assertTrue(message.content().contains("updated_at: 2026-05-04T10:05:00Z"));
        assertTrue(message.content().contains("PLAN 阶段只允许只读工具。"));
    }

    @Test
    void shouldRejectEmptyRecalledMemoryList() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> renderer.render(List.of())
        );

        assertTrue(exception.getMessage().contains("must not be empty"));
    }
}
