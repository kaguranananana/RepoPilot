package com.repopilot.core.context;

import java.util.List;
import java.util.Objects;

/**
 * 模型生成的结构化上下文摘要。
 * 字段保持固定，避免后续 prompt 消费方需要猜测模型输出结构。
 */
public record StructuredContextSummary(
        String userGoal,
        String currentPhase,
        String planState,
        List<String> touchedFiles,
        List<String> importantFindings,
        List<String> failedCommands,
        List<String> decisions,
        List<String> nextActions
) {

    public StructuredContextSummary {
        userGoal = requireNonBlank(userGoal, "userGoal must not be blank.");
        currentPhase = requireNonBlank(currentPhase, "currentPhase must not be blank.");
        planState = requireNonBlank(planState, "planState must not be blank.");
        touchedFiles = immutableList(touchedFiles, "touchedFiles");
        importantFindings = immutableList(importantFindings, "importantFindings");
        failedCommands = immutableList(failedCommands, "failedCommands");
        decisions = immutableList(decisions, "decisions");
        nextActions = immutableList(nextActions, "nextActions");
    }

    public String renderForContextSummary() {
        StringBuilder builder = new StringBuilder();
        builder.append("model_context_summary").append(System.lineSeparator());
        appendSingleLine(builder, "user_goal", userGoal);
        appendSingleLine(builder, "current_phase", currentPhase);
        appendSingleLine(builder, "plan_state", planState);
        appendList(builder, "touched_files", touchedFiles);
        appendList(builder, "important_findings", importantFindings);
        appendList(builder, "failed_commands", failedCommands);
        appendList(builder, "decisions", decisions);
        appendList(builder, "next_actions", nextActions);
        return builder.toString().stripTrailing();
    }

    public WorkingMemorySnapshot toWorkingMemorySnapshot(WorkingMemorySnapshot previousSnapshot) {
        Objects.requireNonNull(previousSnapshot, "previousSnapshot must not be null.");
        // 模型摘要路径已经用结构化字段替代旧高保真历史，
        // 因此最终 working_memory 也必须来自摘要字段，避免长 prompt 从旧 task_goal 泄漏回来。
        return new WorkingMemorySnapshot(
                userGoal,
                importantFindings,
                List.of(),
                List.of(),
                touchedFiles,
                renderNextAction(),
                touchedFiles,
                List.of(),
                failedCommands,
                List.of(),
                decisions,
                previousSnapshot.compactionCount(),
                previousSnapshot.archivedMessageCount(),
                previousSnapshot.resumeCheckpointId(),
                previousSnapshot.latestArchiveReason()
        );
    }

    private String renderNextAction() {
        if (nextActions.isEmpty()) {
            return "继续推进当前任务";
        }
        return nextActions.get(0);
    }

    private static void appendSingleLine(StringBuilder builder, String label, String value) {
        builder.append(label)
                .append(": ")
                .append(value)
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

    private static List<String> immutableList(List<String> values, String fieldName) {
        Objects.requireNonNull(values, fieldName + " must not be null.");
        return values.stream()
                .map(value -> requireNonBlank(value, fieldName + " entry must not be blank."))
                .toList();
    }

    private static String requireNonBlank(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
