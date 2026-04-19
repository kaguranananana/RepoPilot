package com.repopilot.cli.interactive;

import java.util.Objects;

/**
 * 用户显式 Skill 命令的结构化结果。
 * 当前只保留两个最小字段：
 * 1. 要激活的 Skill 名称
 * 2. 命令后剩余的真实任务文本
 */
public record UserSkillCommand(
        String skillName,
        String remainingPrompt
) {

    public UserSkillCommand {
        skillName = requireNonBlank(skillName, "skillName must not be blank.");
        remainingPrompt = normalizeOptionalPrompt(remainingPrompt);
    }

    public boolean hasRemainingPrompt() {
        return remainingPrompt != null;
    }

    private static String requireNonBlank(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    private static String normalizeOptionalPrompt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
