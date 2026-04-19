package com.repopilot.core.context;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.tool.ToolExecutionResult;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * 运行中的 short-term memory 聚合器。
 * 它只维护结构化状态，不直接决定消息窗口如何裁剪。
 */
public final class WorkingMemory {

    private final ContextCompactionPolicy policy;
    private final LinkedHashSet<String> confirmedFacts;
    private final ArrayDeque<String> recentToolResults;
    private final LinkedHashSet<String> currentBlockers;
    private final LinkedHashSet<String> artifactReferences;
    private final LinkedHashSet<String> keyFilesRead;
    private final LinkedHashSet<String> importantToolCalls;
    private final LinkedHashSet<String> toolErrors;
    private final LinkedHashSet<String> userConstraints;
    private final LinkedHashSet<String> confirmedOutcomes;

    private String taskGoal;
    private String nextAction;
    private int compactionCount;
    private int archivedMessageCount;
    private String resumeCheckpointId;
    private String latestArchiveReason;
    private boolean dirty;
    private WorkingMemorySnapshot lastSnapshot;

    private WorkingMemory(ContextCompactionPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null.");
        this.confirmedFacts = new LinkedHashSet<>();
        this.recentToolResults = new ArrayDeque<>();
        this.currentBlockers = new LinkedHashSet<>();
        this.artifactReferences = new LinkedHashSet<>();
        this.keyFilesRead = new LinkedHashSet<>();
        this.importantToolCalls = new LinkedHashSet<>();
        this.toolErrors = new LinkedHashSet<>();
        this.userConstraints = new LinkedHashSet<>();
        this.confirmedOutcomes = new LinkedHashSet<>();
        this.nextAction = "继续推进当前任务";
        this.dirty = true;
    }

    public static WorkingMemory initialize(List<ConversationMessage> messages, ContextCompactionPolicy policy) {
        WorkingMemory workingMemory = new WorkingMemory(policy);
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty.");
        }

        // 初始化阶段只从现有消息提取确定性信息。
        // 这里不做自由文本猜测，只遵循“首条用户消息是任务目标，其余用户消息是显式约束”的规则。
        for (ConversationMessage message : messages) {
            if (message.role() != MessageRole.USER || message.content().isBlank()) {
                continue;
            }
            if (workingMemory.taskGoal == null) {
                workingMemory.taskGoal = message.content().strip();
                continue;
            }
            workingMemory.userConstraints.add(message.content().strip());
        }

        if (workingMemory.taskGoal == null) {
            throw new IllegalArgumentException("At least one non-blank USER message is required to initialize working memory.");
        }

        return workingMemory;
    }

    public static WorkingMemory restore(WorkingMemorySnapshot snapshot, ContextCompactionPolicy policy) {
        Objects.requireNonNull(snapshot, "snapshot must not be null.");

        WorkingMemory workingMemory = new WorkingMemory(policy);
        workingMemory.taskGoal = snapshot.taskGoal();
        workingMemory.confirmedFacts.addAll(snapshot.confirmedFacts());
        workingMemory.recentToolResults.addAll(snapshot.recentToolResults());
        workingMemory.currentBlockers.addAll(snapshot.currentBlockers());
        workingMemory.artifactReferences.addAll(snapshot.artifactReferences());
        workingMemory.nextAction = snapshot.nextAction();
        workingMemory.keyFilesRead.addAll(snapshot.keyFilesRead());
        workingMemory.importantToolCalls.addAll(snapshot.importantToolCalls());
        workingMemory.toolErrors.addAll(snapshot.toolErrors());
        workingMemory.userConstraints.addAll(snapshot.userConstraints());
        workingMemory.confirmedOutcomes.addAll(snapshot.confirmedOutcomes());
        workingMemory.compactionCount = snapshot.compactionCount();
        workingMemory.archivedMessageCount = snapshot.archivedMessageCount();
        workingMemory.resumeCheckpointId = snapshot.resumeCheckpointId();
        workingMemory.latestArchiveReason = snapshot.latestArchiveReason();
        workingMemory.dirty = false;
        workingMemory.lastSnapshot = snapshot;
        return workingMemory;
    }

    public void recordToolCall(ToolCall toolCall) {
        Objects.requireNonNull(toolCall, "toolCall must not be null.");
        importantToolCalls.add(formatToolCall(toolCall));
        dirty = true;
    }

    public void recordToolResult(ToolCall toolCall, ToolExecutionResult executionResult) {
        Objects.requireNonNull(toolCall, "toolCall must not be null.");
        Objects.requireNonNull(executionResult, "executionResult must not be null.");

        String formattedToolCall = formatToolCall(toolCall);
        String formattedToolResult = formattedToolCall + " -> " + executionResult.status() + ": " + executionResult.output();
        appendRecentToolResult(formattedToolResult);

        // 读取文件成功后才记入 key_files_read，
        // 这样 `context_summary` 记录的是“已经发生”的事实，而不是尚未完成的意图。
        if (executionResult.isSuccess() && "read_file".equals(toolCall.toolName())) {
            String path = requireArgument(toolCall.arguments(), "path");
            keyFilesRead.add(path);
            confirmedFacts.add("已读取文件: " + path);
        }

        // 写文件成功后把产出物引用和确认结果都写入快照，
        // 后续即使原始高保真消息被压缩，也仍然能知道产物落到了哪里。
        if (executionResult.isSuccess() && "write_file".equals(toolCall.toolName())) {
            String path = requireArgument(toolCall.arguments(), "path");
            artifactReferences.add(path);
            confirmedOutcomes.add("已写入产出物: " + path);
        }

        // 工具错误需要同时进入“阻塞点”和“错误摘要”，
        // 因为这两层分别服务于下一步推理和历史排障。
        if (!executionResult.isSuccess()) {
            String blocker = formattedToolCall + " -> " + executionResult.output();
            currentBlockers.add(blocker);
            toolErrors.add(blocker);
        }

        nextAction = resolveNextAction(toolCall, executionResult);
        dirty = true;
    }

    public void recordCompaction(String reason, int archivedMessages) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank.");
        }
        if (archivedMessages <= 0) {
            throw new IllegalArgumentException("archivedMessages must be greater than zero.");
        }
        compactionCount += 1;
        archivedMessageCount += archivedMessages;
        resumeCheckpointId = "compaction-" + compactionCount;
        latestArchiveReason = reason.strip();
        dirty = true;
    }

    public WorkingMemorySnapshot snapshot() {
        if (!dirty && lastSnapshot != null) {
            return lastSnapshot;
        }

        lastSnapshot = new WorkingMemorySnapshot(
                taskGoal,
                List.copyOf(confirmedFacts),
                List.copyOf(recentToolResults),
                List.copyOf(currentBlockers),
                List.copyOf(artifactReferences),
                nextAction,
                List.copyOf(keyFilesRead),
                List.copyOf(importantToolCalls),
                List.copyOf(toolErrors),
                List.copyOf(userConstraints),
                List.copyOf(confirmedOutcomes),
                compactionCount,
                archivedMessageCount,
                resumeCheckpointId,
                latestArchiveReason
        );
        dirty = false;
        return lastSnapshot;
    }

    private void appendRecentToolResult(String formattedToolResult) {
        recentToolResults.addLast(formattedToolResult);
        while (recentToolResults.size() > policy.maxRecentToolResults()) {
            recentToolResults.removeFirst();
        }
    }

    private String resolveNextAction(ToolCall toolCall, ToolExecutionResult executionResult) {
        if (executionResult.isSuccess()) {
            return "继续推进当前任务";
        }
        return "处理阻塞并继续当前任务: " + toolCall.toolName();
    }

    private String formatToolCall(ToolCall toolCall) {
        StringJoiner joiner = new StringJoiner(", ");
        toolCall.arguments().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> joiner.add(entry.getKey() + "=" + entry.getValue()));
        return toolCall.toolName() + "(" + joiner + ")";
    }

    private String requireArgument(Map<String, String> arguments, String key) {
        String value = arguments.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return value.strip();
    }
}
