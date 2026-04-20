package com.repopilot.core.edit;

import java.util.Objects;

/**
 * 单文件补丁应用结果。
 * 摘要字段使用稳定多行键值对，便于审批、trace 和终端展示复用同一份事实。
 */
public record PatchApplyResult(
        String displayPath,
        ChangeType changeType,
        int beforeLineCount,
        int afterLineCount,
        int addedLineCount,
        int removedLineCount,
        String summary
) {

    public PatchApplyResult {
        displayPath = requireNonBlank(displayPath, "displayPath must not be blank.");
        changeType = Objects.requireNonNull(changeType, "changeType must not be null.");
        if (beforeLineCount < 0 || afterLineCount < 0 || addedLineCount < 0 || removedLineCount < 0) {
            throw new IllegalArgumentException("line counts must not be negative.");
        }
        summary = requireNonBlank(summary, "summary must not be blank.");
    }

    public enum ChangeType {
        MODIFY,
        NO_CHANGES
    }

    private static String requireNonBlank(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
