package com.repopilot.core.context;

import com.repopilot.core.model.ConversationMessage;
import java.util.List;
import java.util.Objects;

/**
 * 结构化摘要模型的输入。
 * archivedMessages 是将被模型摘要替代的高保真历史，snapshot 是已有确定性工作记忆。
 */
public record StructuredContextSummaryRequest(
        List<ConversationMessage> archivedMessages,
        WorkingMemorySnapshot workingMemorySnapshot
) {

    public StructuredContextSummaryRequest {
        archivedMessages = List.copyOf(Objects.requireNonNull(archivedMessages, "archivedMessages must not be null."));
        if (archivedMessages.isEmpty()) {
            throw new IllegalArgumentException("archivedMessages must not be empty.");
        }
        workingMemorySnapshot = Objects.requireNonNull(
                workingMemorySnapshot,
                "workingMemorySnapshot must not be null."
        );
    }
}
