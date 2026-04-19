package com.repopilot.core.context;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 上下文压缩器。
 * 负责把高保真历史裁剪为“固定前缀 + 结构化摘要 + 最近窗口”。
 */
public final class ContextCompactor {

    private final ContextCompactionPolicy policy;

    public ContextCompactor(ContextCompactionPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null.");
    }

    public ContextCompactionPolicy policy() {
        return policy;
    }

    public CompactionResult compact(List<ConversationMessage> messages, WorkingMemorySnapshot snapshot) {
        Objects.requireNonNull(messages, "messages must not be null.");
        Objects.requireNonNull(snapshot, "snapshot must not be null.");

        List<ConversationMessage> systemMessages = new ArrayList<>();
        List<ConversationMessage> highFidelityMessages = new ArrayList<>();
        for (ConversationMessage message : messages) {
            if (message.role() == MessageRole.SYSTEM) {
                systemMessages.add(message);
                continue;
            }
            if (message.role() == MessageRole.WORKING_MEMORY || message.role() == MessageRole.CONTEXT_SUMMARY) {
                continue;
            }
            highFidelityMessages.add(message);
        }

        int retainedCount = Math.min(policy.retainedHighFidelityMessages(), highFidelityMessages.size());
        int retainedStartIndex = highFidelityMessages.size() - retainedCount;
        List<ConversationMessage> retainedMessages = highFidelityMessages.subList(retainedStartIndex, highFidelityMessages.size());
        int compactedCount = retainedStartIndex;

        // 先保留所有 system 消息，
        // 因为它们属于固定上下文，不应该被压缩动作吞掉。
        List<ConversationMessage> compactedMessages = new ArrayList<>(systemMessages);
        // 再插入 working_memory，
        // 让模型先看到当前回合真正需要的结构化状态。
        compactedMessages.add(snapshot.toWorkingMemoryMessage());
        // 然后补 context_summary，
        // 这样更早历史即使被裁掉，也仍有结构化归档留在窗口里。
        if (snapshot.hasContextSummaryContent()) {
            compactedMessages.add(snapshot.toContextSummaryMessage());
        }
        // 最后拼接最近高保真消息，
        // 保持模型对最新工具调用与对话轮次的细粒度感知。
        compactedMessages.addAll(retainedMessages);

        return new CompactionResult(List.copyOf(compactedMessages), compactedCount);
    }

    public record CompactionResult(
            List<ConversationMessage> messages,
            int compactedHighFidelityMessageCount
    ) {

        public CompactionResult {
            messages = List.copyOf(messages);
            if (compactedHighFidelityMessageCount < 0) {
                throw new IllegalArgumentException("compactedHighFidelityMessageCount must not be negative.");
            }
        }
    }
}
