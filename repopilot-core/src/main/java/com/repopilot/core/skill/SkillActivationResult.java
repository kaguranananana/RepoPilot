package com.repopilot.core.skill;

import com.repopilot.core.model.ConversationMessage;
import java.util.List;

/**
 * Skill 激活结果。
 * 它显式区分“这次是否新激活”和“这次需要向历史追加哪些消息”，
 * 避免调用方再从输出文本里反推真实状态。
 */
public record SkillActivationResult(
        String skillName,
        boolean activatedNow,
        List<ConversationMessage> appendedMessages,
        String output
) {

    public SkillActivationResult {
        skillName = requireNonBlank(skillName, "Skill name must not be blank.");
        output = requireNonBlank(output, "Skill activation output must not be blank.");
        appendedMessages = appendedMessages == null ? List.of() : List.copyOf(appendedMessages);
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
