package com.repopilot.cli.interactive;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 解析用户显式 Skill 命令。
 * 当前只支持两种明确前缀：
 * 1. `/skill-name`
 * 2. `$skill-name`
 */
public final class UserSkillCommandParser {

    private static final Pattern SKILL_NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]*");

    public Optional<UserSkillCommand> parse(String input) {
        Objects.requireNonNull(input, "input must not be null.");

        String normalizedInput = input.strip();
        if (normalizedInput.isEmpty()) {
            return Optional.empty();
        }
        if (!isSkillPrefix(normalizedInput.charAt(0))) {
            return Optional.empty();
        }

        int separatorIndex = findFirstWhitespace(normalizedInput);
        String skillName = extractSkillName(normalizedInput, separatorIndex);
        String remainingPrompt = separatorIndex < 0 ? null : normalizedInput.substring(separatorIndex).strip();

        // 这里显式检查“第二个 token 本身又是一个合法 Skill 命令”的情况，
        // 直接拒绝 `/debug /review` 这类一次激活多个 Skill 的输入，
        // 避免在第一版里偷偷做多 Skill 编排。
        if (remainingPrompt != null && startsWithExplicitSkillCommand(remainingPrompt)) {
            throw new IllegalArgumentException("一次只能显式激活一个 Skill。");
        }

        return Optional.of(new UserSkillCommand(skillName, remainingPrompt));
    }

    private String extractSkillName(String input, int separatorIndex) {
        String skillName = separatorIndex < 0 ? input.substring(1) : input.substring(1, separatorIndex);
        if (skillName.isBlank()) {
            throw new IllegalArgumentException("Skill 名称不能为空。");
        }
        if (!SKILL_NAME_PATTERN.matcher(skillName).matches()) {
            throw new IllegalArgumentException("Skill 名称格式非法: " + skillName);
        }
        return skillName;
    }

    private boolean startsWithExplicitSkillCommand(String input) {
        if (input.isEmpty() || !isSkillPrefix(input.charAt(0))) {
            return false;
        }

        int separatorIndex = findFirstWhitespace(input);
        String candidateName = separatorIndex < 0 ? input.substring(1) : input.substring(1, separatorIndex);
        if (candidateName.isBlank()) {
            return false;
        }
        return SKILL_NAME_PATTERN.matcher(candidateName).matches();
    }

    private boolean isSkillPrefix(char value) {
        return value == '/' || value == '$';
    }

    private int findFirstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }
}
