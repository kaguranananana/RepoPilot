package com.repopilot.core.skill;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import java.util.List;
import java.util.Objects;

/**
 * 统一处理 Skill 激活主链路。
 * 当前只做最小能力：
 * 1. 校验 Skill 是否存在
 * 2. 判断是否重复激活
 * 3. 读取 `SKILL.md` 正文
 * 4. 生成固定格式的 `SYSTEM` 注入消息
 */
public final class SkillActivationService {

    private final SkillLoader skillLoader;

    public SkillActivationService(SkillLoader skillLoader) {
        this.skillLoader = Objects.requireNonNull(skillLoader, "skillLoader must not be null.");
    }

    public SkillActivationResult activate(ActivatedSkillSet activatedSkillSet, String skillName) {
        Objects.requireNonNull(activatedSkillSet, "activatedSkillSet must not be null.");
        String safeSkillName = requireNonBlank(skillName, "Skill name must not be blank.");

        if (activatedSkillSet.contains(safeSkillName)) {
            return new SkillActivationResult(
                    safeSkillName,
                    false,
                    List.of(),
                    "Skill %s 已激活。".formatted(safeSkillName)
            );
        }

        SkillContent skillContent = skillLoader.loadContent(safeSkillName);
        ConversationMessage activatedMessage = buildActivatedSkillMessage(skillContent);

        return new SkillActivationResult(
                safeSkillName,
                true,
                List.of(activatedMessage),
                "Skill %s 已激活。".formatted(safeSkillName)
        );
    }

    private ConversationMessage buildActivatedSkillMessage(SkillContent skillContent) {
        SkillDescriptor descriptor = skillContent.descriptor();

        // 这里按固定格式构造激活消息，
        // 第一行固定声明这是 Activated Skill，
        // 第二、三行固定写 name 和 source，
        // 最后再拼完整正文，确保后续可从历史中可靠重建激活状态。
        String content = """
                # Activated Skill
                name: %s
                source: %s

                %s
                """.formatted(
                descriptor.name(),
                descriptor.source(),
                skillContent.body()
        ).strip();

        return new ConversationMessage(MessageRole.SYSTEM, content);
    }

    private String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
