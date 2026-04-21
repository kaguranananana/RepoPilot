package com.repopilot.core.context;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ToolCall;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 上下文压缩器。
 * 负责把高保真历史裁剪为“固定前缀 + 结构化摘要 + 最近窗口”。
 */
public final class ContextCompactor {

    private static final Set<String> MICROCOMPACTABLE_TOOLS = Set.of(
            "read_file",
            "grep_files",
            "run_command",
            "apply_patch"
    );

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
        int protocolSafeRetainedStartIndex = expandStartToToolCallBoundary(highFidelityMessages, retainedStartIndex);
        List<ConversationMessage> retainedMessages =
                highFidelityMessages.subList(protocolSafeRetainedStartIndex, highFidelityMessages.size());
        MicrocompactResult microcompactResult = microcompactToolResults(retainedMessages);
        int compactedCount = protocolSafeRetainedStartIndex;

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
        compactedMessages.addAll(microcompactResult.messages());

        return new CompactionResult(
                List.copyOf(compactedMessages),
                compactedCount,
                microcompactResult.microcompactedToolResultCount()
        );
    }

    public CompactionResult compactWithStructuredSummary(
            List<ConversationMessage> messages,
            WorkingMemorySnapshot snapshot,
            StructuredContextSummary structuredSummary
    ) {
        Objects.requireNonNull(messages, "messages must not be null.");
        Objects.requireNonNull(snapshot, "snapshot must not be null.");
        Objects.requireNonNull(structuredSummary, "structuredSummary must not be null.");

        List<ConversationMessage> systemMessages = new ArrayList<>();
        int highFidelityMessageCount = 0;
        for (ConversationMessage message : messages) {
            if (message.role() == MessageRole.SYSTEM) {
                systemMessages.add(message);
                continue;
            }
            if (message.role() != MessageRole.WORKING_MEMORY && message.role() != MessageRole.CONTEXT_SUMMARY) {
                highFidelityMessageCount += 1;
            }
        }

        // 结构化模型摘要路径用于规则压缩仍超 token budget 的场景，
        // 因此这里不再保留最近高保真窗口，而是用模型摘要整体替代旧历史。
        List<ConversationMessage> compactedMessages = new ArrayList<>(systemMessages);
        compactedMessages.add(snapshot.toWorkingMemoryMessage());
        compactedMessages.add(ConversationMessage.contextSummary(renderStructuredContextSummary(
                snapshot,
                structuredSummary
        )));

        return new CompactionResult(
                List.copyOf(compactedMessages),
                highFidelityMessageCount,
                0
        );
    }

    private String renderStructuredContextSummary(
            WorkingMemorySnapshot snapshot,
            StructuredContextSummary structuredSummary
    ) {
        if (!snapshot.hasContextSummaryContent()) {
            return structuredSummary.renderForContextSummary();
        }
        return snapshot.renderContextSummary()
                + System.lineSeparator()
                + System.lineSeparator()
                + structuredSummary.renderForContextSummary();
    }

    private MicrocompactResult microcompactToolResults(List<ConversationMessage> retainedMessages) {
        // 先收集 retained window 内的 assistant tool_calls，
        // 后续 TOOL 消息才能用 tool_call_id 找回工具名和参数。
        Map<String, ToolCall> toolCallsById = collectToolCallsById(retainedMessages);
        // 再从尾部找出最近的 TOOL 结果，
        // 这些结果保持原文，避免刚执行完的工具输出被过早压缩。
        Set<String> rawToolResultIds = latestRawToolResultIds(retainedMessages);
        List<ConversationMessage> compactedMessages = new ArrayList<>(retainedMessages.size());
        int microcompactedCount = 0;

        for (ConversationMessage message : retainedMessages) {
            ToolCall toolCall = toolCallsById.get(message.toolCallId());
            if (shouldMicrocompact(message, toolCall, rawToolResultIds)) {
                compactedMessages.add(ConversationMessage.toolResult(
                        message.toolCallId(),
                        renderMicrocompactedToolResult(toolCall, message.content())
                ));
                microcompactedCount += 1;
                continue;
            }
            compactedMessages.add(message);
        }

        return new MicrocompactResult(List.copyOf(compactedMessages), microcompactedCount);
    }

    private Map<String, ToolCall> collectToolCallsById(List<ConversationMessage> messages) {
        Map<String, ToolCall> toolCallsById = new LinkedHashMap<>();
        for (ConversationMessage message : messages) {
            for (ToolCall toolCall : message.toolCalls()) {
                toolCallsById.put(toolCall.id(), toolCall);
            }
        }
        return toolCallsById;
    }

    private Set<String> latestRawToolResultIds(List<ConversationMessage> messages) {
        Set<String> rawToolResultIds = new HashSet<>();
        for (int index = messages.size() - 1; index >= 0; index--) {
            ConversationMessage message = messages.get(index);
            if (message.role() != MessageRole.TOOL) {
                continue;
            }
            rawToolResultIds.add(message.toolCallId());
            if (rawToolResultIds.size() >= policy.maxRecentToolResults()) {
                break;
            }
        }
        return rawToolResultIds;
    }

    private boolean shouldMicrocompact(
            ConversationMessage message,
            ToolCall toolCall,
            Set<String> rawToolResultIds
    ) {
        return message.role() == MessageRole.TOOL
                && toolCall != null
                && MICROCOMPACTABLE_TOOLS.contains(toolCall.toolName())
                && !rawToolResultIds.contains(message.toolCallId());
    }

    private String renderMicrocompactedToolResult(ToolCall toolCall, String originalContent) {
        return String.join(System.lineSeparator(),
                "microcompact_tool_result",
                "- tool_call_id: " + toolCall.id(),
                "- tool_name: " + toolCall.toolName(),
                "- arguments: " + renderArguments(toolCall.arguments()),
                "- original_characters: " + originalContent.length(),
                "- original_content: omitted_from_prompt"
        );
    }

    private String renderArguments(Map<String, String> arguments) {
        if (arguments.isEmpty()) {
            return "none";
        }
        return arguments.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private int expandStartToToolCallBoundary(List<ConversationMessage> highFidelityMessages, int retainedStartIndex) {
        if (retainedStartIndex <= 0 || retainedStartIndex >= highFidelityMessages.size()) {
            return retainedStartIndex;
        }

        ConversationMessage firstRetainedMessage = highFidelityMessages.get(retainedStartIndex);
        if (firstRetainedMessage.role() != MessageRole.TOOL) {
            return retainedStartIndex;
        }

        // OpenAI-compatible 协议要求 TOOL 消息必须紧跟对应的 assistant tool_calls。
        // 如果最近窗口从 TOOL 消息中间开始，就向前扩展到发起该 tool_call 的 ASSISTANT 消息。
        for (int index = retainedStartIndex - 1; index >= 0; index--) {
            ConversationMessage candidate = highFidelityMessages.get(index);
            if (candidate.role() != MessageRole.ASSISTANT || candidate.toolCalls().isEmpty()) {
                continue;
            }
            boolean ownsToolResult = candidate.toolCalls().stream()
                    .anyMatch(toolCall -> toolCall.id().equals(firstRetainedMessage.toolCallId()));
            if (ownsToolResult) {
                return index;
            }
        }
        return retainedStartIndex;
    }

    public record CompactionResult(
            List<ConversationMessage> messages,
            int compactedHighFidelityMessageCount,
            int microcompactedToolResultCount
    ) {

        public CompactionResult {
            messages = List.copyOf(messages);
            if (compactedHighFidelityMessageCount < 0) {
                throw new IllegalArgumentException("compactedHighFidelityMessageCount must not be negative.");
            }
            if (microcompactedToolResultCount < 0) {
                throw new IllegalArgumentException("microcompactedToolResultCount must not be negative.");
            }
        }
    }

    private record MicrocompactResult(
            List<ConversationMessage> messages,
            int microcompactedToolResultCount
    ) {

        private MicrocompactResult {
            messages = List.copyOf(messages);
            if (microcompactedToolResultCount < 0) {
                throw new IllegalArgumentException("microcompactedToolResultCount must not be negative.");
            }
        }
    }
}
