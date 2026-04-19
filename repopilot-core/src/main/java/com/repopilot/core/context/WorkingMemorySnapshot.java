package com.repopilot.core.context;

import com.repopilot.core.model.ConversationMessage;
import java.util.List;
import java.util.Objects;

/**
 * 结构化 short-term memory 快照。
 * 一份快照同时承载两层信息：
 * 1. `working_memory`：给当前回合继续推理用的最小稳定事实。
 * 2. `context_summary`：给更早历史轨迹做结构化归档。
 */
public record WorkingMemorySnapshot(
        String taskGoal,
        List<String> confirmedFacts,
        List<String> recentToolResults,
        List<String> currentBlockers,
        List<String> artifactReferences,
        String nextAction,
        List<String> keyFilesRead,
        List<String> importantToolCalls,
        List<String> toolErrors,
        List<String> userConstraints,
        List<String> confirmedOutcomes,
        int compactionCount,
        int archivedMessageCount,
        String resumeCheckpointId,
        String latestArchiveReason
) {

    public WorkingMemorySnapshot {
        taskGoal = requireNonBlank(taskGoal, "taskGoal must not be blank.");
        confirmedFacts = immutableList(confirmedFacts);
        recentToolResults = immutableList(recentToolResults);
        currentBlockers = immutableList(currentBlockers);
        artifactReferences = immutableList(artifactReferences);
        nextAction = requireNonBlank(nextAction, "nextAction must not be blank.");
        keyFilesRead = immutableList(keyFilesRead);
        importantToolCalls = immutableList(importantToolCalls);
        toolErrors = immutableList(toolErrors);
        userConstraints = immutableList(userConstraints);
        confirmedOutcomes = immutableList(confirmedOutcomes);
        if (compactionCount < 0) {
            throw new IllegalArgumentException("compactionCount must not be negative.");
        }
        if (archivedMessageCount < 0) {
            throw new IllegalArgumentException("archivedMessageCount must not be negative.");
        }
        resumeCheckpointId = normalizeOptionalText(resumeCheckpointId);
        latestArchiveReason = normalizeOptionalText(latestArchiveReason);
    }

    public ConversationMessage toWorkingMemoryMessage() {
        return ConversationMessage.workingMemory(renderWorkingMemory());
    }

    public ConversationMessage toContextSummaryMessage() {
        return ConversationMessage.contextSummary(renderContextSummary());
    }

    public boolean hasContextSummaryContent() {
        return !keyFilesRead.isEmpty()
                || !importantToolCalls.isEmpty()
                || !toolErrors.isEmpty()
                || !userConstraints.isEmpty()
                || !confirmedOutcomes.isEmpty()
                || archivedMessageCount > 0;
    }

    public String renderWorkingMemory() {
        StringBuilder builder = new StringBuilder();
        builder.append("working_memory").append(System.lineSeparator());
        appendSingleLine(builder, "task_goal", taskGoal);
        appendList(builder, "confirmed_facts", confirmedFacts);
        appendList(builder, "recent_tool_results", recentToolResults);
        appendList(builder, "current_blockers", currentBlockers);
        appendList(builder, "artifact_references", artifactReferences);
        appendSingleLine(builder, "next_action", nextAction);
        return builder.toString().stripTrailing();
    }

    public String renderContextSummary() {
        StringBuilder builder = new StringBuilder();
        builder.append("context_summary").append(System.lineSeparator());
        appendList(builder, "user_constraints", userConstraints);
        appendList(builder, "key_files_read", keyFilesRead);
        appendList(builder, "important_tool_calls", importantToolCalls);
        appendList(builder, "tool_errors", toolErrors);
        appendList(builder, "confirmed_outcomes", confirmedOutcomes);
        appendArchiveState(builder);
        return builder.toString().stripTrailing();
    }

    private void appendArchiveState(StringBuilder builder) {
        builder.append("archive_state:").append(System.lineSeparator());
        builder.append("- compaction_count: ").append(compactionCount).append(System.lineSeparator());
        builder.append("- archived_message_count: ").append(archivedMessageCount).append(System.lineSeparator());
        builder.append("- checkpoint_id: ").append(renderOptionalText(resumeCheckpointId)).append(System.lineSeparator());
        builder.append("- latest_archive_reason: ").append(renderOptionalText(latestArchiveReason)).append(System.lineSeparator());
    }

    private static void appendSingleLine(StringBuilder builder, String label, String value) {
        builder.append(label)
                .append(": ")
                .append(renderOptionalText(value))
                .append(System.lineSeparator());
    }

    private static void appendList(StringBuilder builder, String label, List<String> values) {
        builder.append(label).append(":").append(System.lineSeparator());
        if (values.isEmpty()) {
            builder.append("- none").append(System.lineSeparator());
            return;
        }
        for (String value : values) {
            builder.append("- ").append(value).append(System.lineSeparator());
        }
    }

    private static List<String> immutableList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(value -> requireNonBlank(value, "snapshot list entry must not be blank."))
                .toList();
    }

    private static String renderOptionalText(String value) {
        return value == null ? "none" : value;
    }

    private static String requireNonBlank(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    private static String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
