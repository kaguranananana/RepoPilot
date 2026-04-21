package com.repopilot.core.agent;

import com.repopilot.core.context.ContextCompactionPolicy;
import com.repopilot.core.context.ContextCompactor;
import com.repopilot.core.context.ContextCompactionDecision;
import com.repopilot.core.context.ContextCompactionTrigger;
import com.repopilot.core.context.ContextInputTokenEstimator;
import com.repopilot.core.context.StructuredContextSummary;
import com.repopilot.core.context.StructuredContextSummaryGenerator;
import com.repopilot.core.context.StructuredContextSummaryRequest;
import com.repopilot.core.context.WorkingMemory;
import com.repopilot.core.context.WorkingMemorySnapshot;
import com.repopilot.core.agent.loop.ToolCallLoopDetectedException;
import com.repopilot.core.agent.loop.ToolCallLoopDetectionResult;
import com.repopilot.core.agent.loop.ToolCallLoopDetector;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import com.repopilot.core.permission.PermissionPolicy;
import com.repopilot.core.review.DiffReviewService;
import com.repopilot.core.trace.TracePublisher;
import com.repopilot.core.tool.ToolExecutionContext;
import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolRegistry;
import com.repopilot.core.tool.governance.GovernedToolExecutor;
import com.repopilot.protocol.trace.TraceEventType;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * RepoPilot 的最小 ReAct 主循环。
 * 当前版本只做一件事：在“模型输出工具调用”和“模型输出最终回答”之间循环切换。
 */
public class AgentLoop {

    private final GovernedToolExecutor governedToolExecutor;
    private final AgentLoopObserver observer;
    private final TracePublisher tracePublisher;
    private final ContextCompactor contextCompactor;
    private final ContextInputTokenEstimator contextInputTokenEstimator;
    private final StructuredContextSummaryGenerator structuredContextSummaryGenerator;

    public AgentLoop(ToolRegistry toolRegistry) {
        this(
                createDefaultGovernedToolExecutor(toolRegistry),
                AgentLoopObserver.noop(),
                TracePublisher.noop(),
                defaultContextCompactor()
        );
    }

    public AgentLoop(ToolRegistry toolRegistry, AgentLoopObserver observer) {
        this(createDefaultGovernedToolExecutor(toolRegistry), observer, TracePublisher.noop(), defaultContextCompactor());
    }

    public AgentLoop(ToolRegistry toolRegistry, TracePublisher tracePublisher) {
        this(createDefaultGovernedToolExecutor(toolRegistry), AgentLoopObserver.noop(), tracePublisher, defaultContextCompactor());
    }

    public AgentLoop(ToolRegistry toolRegistry, AgentLoopObserver observer, TracePublisher tracePublisher) {
        this(createDefaultGovernedToolExecutor(toolRegistry), observer, tracePublisher, defaultContextCompactor());
    }

    public AgentLoop(
            ToolRegistry toolRegistry,
            AgentLoopObserver observer,
            TracePublisher tracePublisher,
            ContextCompactor contextCompactor
    ) {
        this(
                createDefaultGovernedToolExecutor(toolRegistry),
                observer,
                tracePublisher,
                contextCompactor,
                ContextInputTokenEstimator.unavailable()
        );
    }

    public AgentLoop(
            ToolRegistry toolRegistry,
            AgentLoopObserver observer,
            TracePublisher tracePublisher,
            ContextCompactor contextCompactor,
            ContextInputTokenEstimator contextInputTokenEstimator
    ) {
        this(
                toolRegistry,
                observer,
                tracePublisher,
                contextCompactor,
                contextInputTokenEstimator,
                StructuredContextSummaryGenerator.unavailable()
        );
    }

    public AgentLoop(
            ToolRegistry toolRegistry,
            AgentLoopObserver observer,
            TracePublisher tracePublisher,
            ContextCompactor contextCompactor,
            ContextInputTokenEstimator contextInputTokenEstimator,
            StructuredContextSummaryGenerator structuredContextSummaryGenerator
    ) {
        this(
                createDefaultGovernedToolExecutor(toolRegistry),
                observer,
                tracePublisher,
                contextCompactor,
                contextInputTokenEstimator,
                structuredContextSummaryGenerator
        );
    }

    public AgentLoop(GovernedToolExecutor governedToolExecutor) {
        this(governedToolExecutor, AgentLoopObserver.noop(), TracePublisher.noop(), defaultContextCompactor());
    }

    public AgentLoop(GovernedToolExecutor governedToolExecutor, AgentLoopObserver observer) {
        this(governedToolExecutor, observer, TracePublisher.noop(), defaultContextCompactor());
    }

    public AgentLoop(GovernedToolExecutor governedToolExecutor, TracePublisher tracePublisher) {
        this(governedToolExecutor, AgentLoopObserver.noop(), tracePublisher, defaultContextCompactor());
    }

    public AgentLoop(
            GovernedToolExecutor governedToolExecutor,
            AgentLoopObserver observer,
            TracePublisher tracePublisher
    ) {
        this(governedToolExecutor, observer, tracePublisher, defaultContextCompactor());
    }

    public AgentLoop(
            GovernedToolExecutor governedToolExecutor,
            AgentLoopObserver observer,
            TracePublisher tracePublisher,
            ContextCompactor contextCompactor
    ) {
        this(governedToolExecutor, observer, tracePublisher, contextCompactor, ContextInputTokenEstimator.unavailable());
    }

    public AgentLoop(
            GovernedToolExecutor governedToolExecutor,
            AgentLoopObserver observer,
            TracePublisher tracePublisher,
            ContextCompactor contextCompactor,
            ContextInputTokenEstimator contextInputTokenEstimator
    ) {
        this(
                governedToolExecutor,
                observer,
                tracePublisher,
                contextCompactor,
                contextInputTokenEstimator,
                StructuredContextSummaryGenerator.unavailable()
        );
    }

    public AgentLoop(
            GovernedToolExecutor governedToolExecutor,
            AgentLoopObserver observer,
            TracePublisher tracePublisher,
            ContextCompactor contextCompactor,
            ContextInputTokenEstimator contextInputTokenEstimator,
            StructuredContextSummaryGenerator structuredContextSummaryGenerator
    ) {
        this.governedToolExecutor = Objects.requireNonNull(governedToolExecutor, "governedToolExecutor must not be null.");
        this.observer = Objects.requireNonNull(observer, "observer must not be null.");
        this.tracePublisher = Objects.requireNonNull(tracePublisher, "tracePublisher must not be null.");
        this.contextCompactor = Objects.requireNonNull(contextCompactor, "contextCompactor must not be null.");
        this.contextInputTokenEstimator = Objects.requireNonNull(
                contextInputTokenEstimator,
                "contextInputTokenEstimator must not be null."
        );
        this.structuredContextSummaryGenerator = Objects.requireNonNull(
                structuredContextSummaryGenerator,
                "structuredContextSummaryGenerator must not be null."
        );
    }

    public AgentLoopResult run(AgentLoopRequest request) {
        List<ConversationMessage> messages = new ArrayList<>(request.messages());
        WorkingMemory workingMemory = WorkingMemory.initialize(messages, contextCompactor.policy());
        ToolCallLoopDetector toolCallLoopDetector =
                new ToolCallLoopDetector(request.toolCallLoopRepeatThreshold());

        for (int step = 0; step < request.maxSteps(); step++) {
            messages = compactMessagesIfNeeded(step + 1, messages, workingMemory);
            // 每一轮 step 开始前先把当前消息快照暴露给观察器，
            // 这样 CLI 或 trace 层就能看到“模型即将基于什么上下文继续推理”。
            observer.onStepStarted(step + 1, List.copyOf(messages));
            // step 已经确定开始后，先记录模型调用开始事件，
            // 这样控制面回放时能知道这一轮是基于多少条历史消息发起的。
            publishModelCallRequested(step + 1, messages);
            ModelResponse response = request.modelAdapter().next(List.copyOf(messages));
            observer.onModelResponse(step + 1, response);
            // 模型返回后立刻补一条响应事件，
            // 显式区分“请求发出”和“结果收到”两个时间点。
            publishModelResponseReceived(step + 1, response);

            if (response instanceof FinalModelResponse finalResponse) {
                ConversationMessage assistantMessage =
                        new ConversationMessage(MessageRole.ASSISTANT, finalResponse.message());
                messages.add(assistantMessage);
                return new AgentLoopResult(messages, finalResponse.message());
            }

            if (response instanceof ToolCallModelResponse toolCallResponse) {
                // 先把 assistant 的 tool_calls 记录进消息历史，
                // 这样下一轮真实模型调用时，协议里就能形成
                // `assistant(tool_calls) -> tool(tool_call_id)` 的正确配对关系。
                messages.add(ConversationMessage.assistantToolCalls(toolCallResponse.toolCalls()));

                for (ToolCall toolCall : toolCallResponse.toolCalls()) {
                    // 每个工具调用先进入确定性循环检测，
                    // 这样命中阈值的那一次不会再真正执行工具。
                    ToolCallLoopDetectionResult loopDetectionResult = toolCallLoopDetector.record(toolCall);
                    // 只要连续重复次数达到显式阈值，
                    // 当前回合就必须中断并暴露真实原因。
                    if (loopDetectionResult.loopDetected()) {
                        observer.onToolCallLoopDetected(step + 1, loopDetectionResult);
                        publishToolCallLoopDetected(step + 1, loopDetectionResult);
                        throw new ToolCallLoopDetectedException(loopDetectionResult);
                    }

                    // 先通知工具即将开始，
                    // 让外层观察器能够打印工具名和参数摘要。
                    observer.onToolExecutionStarted(step + 1, toolCall);
                    // 工具真正执行前就先把请求事件写出去，
                    // 即使工具内部马上失败，控制面也能还原本次调用意图。
                    publishToolCallRequested(step + 1, toolCall);
                    workingMemory.recordToolCall(toolCall);
                    ToolExecutionResult executionResult =
                            governedToolExecutor.execute(
                                    new ToolExecutionContext(List.copyOf(messages), request.runMode()),
                                    toolCall.toolName(),
                                    toolCall.arguments()
                            );
                    // 工具返回后立刻通知观察器，
                    // 即使结果是致命错误，也保证外层能先拿到真实失败信息。
                    observer.onToolExecutionFinished(step + 1, toolCall, executionResult);
                    // 工具一返回就立即上报完成事件，
                    // 尤其 FATAL_ERROR 必须先落地，再中断主链路。
                    publishToolCallCompleted(step + 1, toolCall, executionResult);
                    workingMemory.recordToolResult(toolCall, executionResult);

                    // 致命错误说明主链路已经失真，必须立刻暴露真实错误，
                    // 不能继续伪装成一条普通 TOOL 消息喂给下一轮模型。
                    failIfFatal(toolCall.toolName(), executionResult);

                    // 工具结果会被重新注入消息列表，
                    // 让下一轮模型推理能够“看到自己刚刚调用工具后发生了什么”。
                    ConversationMessage toolMessage = ConversationMessage.toolResult(
                            toolCall.id(),
                            formatToolMessage(toolCall.toolName(), executionResult)
                    );
                    messages.add(toolMessage);
                    messages.addAll(executionResult.appendedMessages());

                    // TOOL 消息只有在真正成功追加进历史后才通知观察器，
                    // 这样外层调试输出看到的就是下一轮模型会实际收到的消息内容。
                    observer.onToolMessageAdded(step + 1, toolCall, toolMessage);
                }
            }
        }

        throw new AgentLoopLimitExceededException(request.maxSteps());
    }

    private List<ConversationMessage> compactMessagesIfNeeded(
            int stepNumber,
            List<ConversationMessage> messages,
            WorkingMemory workingMemory
    ) {
        ContextCompactionDecision decision = evaluateContextCompaction(messages);
        // 先按显式阈值判断是否需要压缩，
        // 没超过阈值就直接沿用原始高保真窗口，避免无意义地改写上下文。
        if (!decision.shouldCompact()) {
            return messages;
        }

        List<ConversationMessage> highFidelityMessages = highFidelityMessages(messages);
        int highFidelityCount = highFidelityMessages.size();
        // 先用当前快照跑一次确定性规则压缩，
        // 再判断规则结果是否仍然超过 token budget。
        WorkingMemorySnapshot preCompactionSnapshot = workingMemory.snapshot();
        ContextCompactor.CompactionResult ruleCompactionResult =
                contextCompactor.compact(messages, preCompactionSnapshot);
        boolean structuredSummaryApplied =
                shouldApplyStructuredSummary(ruleCompactionResult.messages(), decision);
        int compactedCount = structuredSummaryApplied
                ? highFidelityCount
                : ruleCompactionResult.compactedHighFidelityMessageCount();

        publishContextCompactionStarted(stepNumber, highFidelityCount, compactedCount, decision);
        // 只有真正折叠旧高保真消息时才写入归档元数据，
        // token budget 也可能只触发工具结果 microcompact，此时 archivedMessageCount 可以是 0。
        if (compactedCount > 0) {
            workingMemory.recordCompaction(decision.trigger().orElseThrow().archiveReason(), compactedCount);
        }
        WorkingMemorySnapshot snapshot = workingMemory.snapshot();
        ContextCompactor.CompactionResult compactionResult;
        WorkingMemorySnapshot resultSnapshot = snapshot;
        if (structuredSummaryApplied) {
            if (highFidelityMessages.isEmpty()) {
                throw new IllegalStateException("规则压缩后仍超过 token budget，但没有可供结构化摘要替代的高保真消息。");
            }
            // 规则压缩仍超预算时，必须让模型生成结构化摘要；
            // 摘要成功后再整体替代高保真历史，避免把超长原文继续塞回窗口。
            StructuredContextSummary structuredSummary = structuredContextSummaryGenerator.generate(
                    new StructuredContextSummaryRequest(highFidelityMessages, snapshot)
            );
            resultSnapshot = structuredSummary.toWorkingMemorySnapshot(snapshot);
            compactionResult = contextCompactor.compactWithStructuredSummary(messages, resultSnapshot, structuredSummary);
        } else {
            // 常规路径仍使用确定性规则压缩，
            // 输出稳定收敛成“system -> working_memory -> context_summary -> recent messages”。
            compactionResult = contextCompactor.compact(messages, snapshot);
        }
        publishContextCompactionCompleted(stepNumber, resultSnapshot, compactionResult, decision, structuredSummaryApplied);
        return new ArrayList<>(compactionResult.messages());
    }

    private boolean shouldApplyStructuredSummary(
            List<ConversationMessage> ruleCompactedMessages,
            ContextCompactionDecision decision
    ) {
        if (decision.trigger().orElseThrow() != ContextCompactionTrigger.TOKEN_BUDGET) {
            return false;
        }
        int estimate = contextInputTokenEstimator.estimateInputTokens(List.copyOf(ruleCompactedMessages));
        if (estimate < 0) {
            throw new IllegalStateException("规则压缩后的输入 token 估算结果不能为负数。");
        }
        return estimate > decision.maxInputTokens().orElseThrow();
    }

    private List<ConversationMessage> highFidelityMessages(List<ConversationMessage> messages) {
        List<ConversationMessage> highFidelityMessages = new ArrayList<>();
        for (ConversationMessage message : messages) {
            if (message.role() == MessageRole.SYSTEM
                    || message.role() == MessageRole.WORKING_MEMORY
                    || message.role() == MessageRole.CONTEXT_SUMMARY) {
                continue;
            }
            highFidelityMessages.add(message);
        }
        return List.copyOf(highFidelityMessages);
    }

    private ContextCompactionDecision evaluateContextCompaction(List<ConversationMessage> messages) {
        OptionalInt estimatedInputTokens = OptionalInt.empty();
        if (contextCompactor.policy().isTokenBudgetEnabled()) {
            int estimate = contextInputTokenEstimator.estimateInputTokens(List.copyOf(messages));
            if (estimate < 0) {
                throw new IllegalStateException("输入 token 估算结果不能为负数。");
            }
            estimatedInputTokens = OptionalInt.of(estimate);
        }
        return contextCompactor.policy().evaluate(messages, estimatedInputTokens);
    }

    private void publishModelCallRequested(int stepNumber, List<ConversationMessage> messages) {
        tracePublisher.publish(new TracePublisher.TraceEvent(
                TraceEventType.MODEL_CALL_REQUESTED,
                "第%d步开始调用模型".formatted(stepNumber),
                Instant.now(),
                Map.of(
                        "stepNumber", Integer.toString(stepNumber),
                        "messageCount", Integer.toString(messages.size())
                )
        ));
    }

    private void publishContextCompactionStarted(
            int stepNumber,
            int highFidelityCount,
            int compactedCount,
            ContextCompactionDecision decision
    ) {
        Map<String, String> metadata = compactionMetadata(stepNumber, highFidelityCount, compactedCount, decision);
        tracePublisher.publish(new TracePublisher.TraceEvent(
                TraceEventType.CONTEXT_COMPACTION_STARTED,
                "第%d步开始压缩上下文".formatted(stepNumber),
                Instant.now(),
                metadata
        ));
    }

    private void publishContextCompactionCompleted(
            int stepNumber,
            WorkingMemorySnapshot snapshot,
            ContextCompactor.CompactionResult compactionResult,
            ContextCompactionDecision decision,
            boolean structuredSummaryApplied
    ) {
        Map<String, String> metadata = compactionMetadata(
                stepNumber,
                -1,
                compactionResult.compactedHighFidelityMessageCount(),
                decision
        );
        metadata.put("messageCountAfter", Integer.toString(compactionResult.messages().size()));
        metadata.put("checkpointId", snapshot.resumeCheckpointId() == null ? "none" : snapshot.resumeCheckpointId());
        metadata.put(
                "microcompactedToolResultCount",
                Integer.toString(compactionResult.microcompactedToolResultCount())
        );
        metadata.put("structuredSummaryApplied", Boolean.toString(structuredSummaryApplied));

        tracePublisher.publish(new TracePublisher.TraceEvent(
                TraceEventType.CONTEXT_COMPACTION_COMPLETED,
                "第%d步完成上下文压缩".formatted(stepNumber),
                Instant.now(),
                metadata
        ));
    }

    private Map<String, String> compactionMetadata(
            int stepNumber,
            int highFidelityCount,
            int compactedCount,
            ContextCompactionDecision decision
    ) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("stepNumber", Integer.toString(stepNumber));
        if (highFidelityCount >= 0) {
            metadata.put("highFidelityMessageCount", Integer.toString(highFidelityCount));
        }
        metadata.put("compactedMessageCount", Integer.toString(compactedCount));
        ContextCompactionTrigger trigger = decision.trigger().orElseThrow();
        metadata.put("trigger", trigger.name());
        decision.estimatedInputTokens()
                .ifPresent(value -> metadata.put("estimatedInputTokens", Integer.toString(value)));
        decision.maxInputTokens()
                .ifPresent(value -> metadata.put("maxInputTokens", Integer.toString(value)));
        return metadata;
    }

    private void publishModelResponseReceived(int stepNumber, ModelResponse response) {
        Map<String, String> metadata = new LinkedHashMap<>();
        // stepNumber 总是显式写入 metadata，
        // 这样后续列表页和统计逻辑都不需要再解析自然语言摘要。
        metadata.put("stepNumber", Integer.toString(stepNumber));
        // 这里明确标出响应是 FINAL 还是 TOOL_CALLS，
        // 让 server 可以快速判断这一轮是结束回合还是继续调用工具。
        metadata.put("responseKind", resolveResponseKind(response));
        if (response instanceof ToolCallModelResponse toolCallModelResponse) {
            // 工具调用个数也单独结构化记录，
            // 避免后续分析还要回头读 assistant 消息正文。
            metadata.put("toolCallCount", Integer.toString(toolCallModelResponse.toolCalls().size()));
        }

        tracePublisher.publish(new TracePublisher.TraceEvent(
                TraceEventType.MODEL_RESPONSE_RECEIVED,
                "第%d步收到模型响应: %s".formatted(stepNumber, metadata.get("responseKind")),
                Instant.now(),
                metadata
        ));
    }

    private void publishToolCallRequested(int stepNumber, ToolCall toolCall) {
        tracePublisher.publish(new TracePublisher.TraceEvent(
                TraceEventType.TOOL_CALL_REQUESTED,
                "第%d步开始调用工具: %s".formatted(stepNumber, toolCall.toolName()),
                Instant.now(),
                Map.of(
                        "stepNumber", Integer.toString(stepNumber),
                        "toolCallId", toolCall.id(),
                        "toolName", toolCall.toolName()
                )
        ));
    }

    private void publishToolCallCompleted(int stepNumber, ToolCall toolCall, ToolExecutionResult executionResult) {
        tracePublisher.publish(new TracePublisher.TraceEvent(
                TraceEventType.TOOL_CALL_COMPLETED,
                "第%d步工具执行完成: %s -> %s".formatted(
                        stepNumber,
                        toolCall.toolName(),
                        executionResult.status()
                ),
                Instant.now(),
                Map.of(
                        "stepNumber", Integer.toString(stepNumber),
                        "toolCallId", toolCall.id(),
                        "toolName", toolCall.toolName(),
                        "toolStatus", executionResult.status().name()
                )
        ));
    }

    private void publishToolCallLoopDetected(
            int stepNumber,
            ToolCallLoopDetectionResult loopDetectionResult
    ) {
        tracePublisher.publish(new TracePublisher.TraceEvent(
                TraceEventType.TOOL_CALL_LOOP_DETECTED,
                "第%d步检测到连续重复工具调用: %s repeatCount=%d".formatted(
                        stepNumber,
                        loopDetectionResult.toolName(),
                        loopDetectionResult.repeatCount()
                ),
                Instant.now(),
                Map.of(
                        "stepNumber", Integer.toString(stepNumber),
                        "toolName", loopDetectionResult.toolName(),
                        "toolCallKey", loopDetectionResult.toolCallKey(),
                        "repeatCount", Integer.toString(loopDetectionResult.repeatCount()),
                        "argumentsSummary", loopDetectionResult.argumentsSummary()
                )
        ));
    }

    private String resolveResponseKind(ModelResponse response) {
        if (response instanceof FinalModelResponse) {
            return "FINAL";
        }
        if (response instanceof ToolCallModelResponse) {
            return "TOOL_CALLS";
        }
        throw new IllegalArgumentException("Unsupported model response type: " + response.getClass().getName());
    }

    private void failIfFatal(String toolName, ToolExecutionResult executionResult) {
        if (executionResult.isFatal()) {
            throw new IllegalStateException(
                    "Tool execution failed fatally: " + toolName + ", output=" + executionResult.output()
            );
        }
    }

    private String formatToolMessage(String toolName, ToolExecutionResult executionResult) {
        return switch (executionResult.status()) {
            case SUCCESS -> "[" + toolName + "] " + executionResult.output();
            case RECOVERABLE_ERROR -> "[" + toolName + ":error] " + executionResult.output();
            case FATAL_ERROR -> throw new IllegalArgumentException(
                    "Fatal tool execution must not be formatted as a TOOL message."
            );
        };
    }

    private static GovernedToolExecutor createDefaultGovernedToolExecutor(ToolRegistry toolRegistry) {
        Objects.requireNonNull(toolRegistry, "toolRegistry must not be null.");

        // 这个默认装配主要服务于现有单元测试和最小构造入口，
        // 因此只保证 AgentLoop 始终走治理流水线，
        // 真实 CLI/runtime 会在外层显式传入工作区权限策略。
        return new GovernedToolExecutor(
                toolRegistry,
                PermissionPolicy.allowAll(),
                new DiffReviewService(Path.of("").toAbsolutePath().normalize())
        );
    }

    private static ContextCompactor defaultContextCompactor() {
        return new ContextCompactor(ContextCompactionPolicy.defaultPolicy());
    }
}
