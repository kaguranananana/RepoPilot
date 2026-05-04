package com.repopilot.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.agent.AgentRunMode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryRecallServiceTest {

    @Test
    void shouldShortCircuitWhenIndexIsEmpty() {
        CountingSelector selector = new CountingSelector(MemoryRecallSelection.empty());
        MemoryRecallService service = new MemoryRecallService(selector);

        MemoryRecallSelection selection = service.recall("先分析方案", AgentRunMode.PLAN, List.of());

        assertTrue(selection.selectedIds().isEmpty());
        assertEquals(0, selector.callCount);
    }

    @Test
    void shouldDelegateToSelectorWhenCandidatesExist() {
        CountingSelector selector = new CountingSelector(new MemoryRecallSelection(
                List.of("project-plan-execute-boundary")
        ));
        MemoryRecallService service = new MemoryRecallService(selector);

        MemoryRecallSelection selection = service.recall(
                "先分析方案",
                AgentRunMode.PLAN,
                List.of(new MemoryIndexEntry(
                        "project-plan-execute-boundary",
                        MemoryType.PROJECT,
                        "Plan 与 Execute 必须分阶段",
                        "该仓库要求先只读取证，再进入修改与验证。",
                        Instant.parse("2026-05-04T10:00:00Z")
                ))
        );

        assertEquals(List.of("project-plan-execute-boundary"), selection.selectedIds());
        assertEquals(1, selector.callCount);
    }

    private static final class CountingSelector implements MemoryRecallSelector {

        private final MemoryRecallSelection scriptedSelection;
        private int callCount;

        private CountingSelector(MemoryRecallSelection scriptedSelection) {
            this.scriptedSelection = scriptedSelection;
        }

        @Override
        public MemoryRecallSelection select(MemoryRecallSelectionRequest request) {
            callCount += 1;
            return scriptedSelection;
        }
    }
}
