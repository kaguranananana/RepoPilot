package com.repopilot.core.skill;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前会话里已经激活过的 Skill 集合。
 * 它只负责记录“哪些 Skill 已经生效”和“对应的注入消息”，
 * 不负责真正的 Skill 加载逻辑。
 */
public final class ActivatedSkillSet {

    private static final String ACTIVATED_SKILL_HEADER = "# Activated Skill";
    private static final String NAME_PREFIX = "name: ";

    private final Map<String, ConversationMessage> activatedMessagesBySkillName;

    private ActivatedSkillSet(Map<String, ConversationMessage> activatedMessagesBySkillName) {
        this.activatedMessagesBySkillName = Map.copyOf(activatedMessagesBySkillName);
    }

    public static ActivatedSkillSet empty() {
        return new ActivatedSkillSet(Map.of());
    }

    public static ActivatedSkillSet fromMessages(List<ConversationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return empty();
        }

        Map<String, ConversationMessage> activatedMessages = new LinkedHashMap<>();
        for (ConversationMessage message : messages) {
            if (message.role() != MessageRole.SYSTEM) {
                continue;
            }

            String skillName = extractSkillName(message.content());
            if (skillName == null) {
                continue;
            }

            // 这里逐条从历史消息重建已激活状态，
            // 这样无论消息来自用户显式激活还是模型工具调用，
            // 后续重复激活判断都只依赖统一的历史事实。
            activatedMessages.putIfAbsent(skillName, message);
        }
        return new ActivatedSkillSet(activatedMessages);
    }

    public boolean contains(String skillName) {
        String safeSkillName = requireNonBlank(skillName, "Skill name must not be blank.");
        return activatedMessagesBySkillName.containsKey(safeSkillName);
    }

    public ActivatedSkillSet append(String skillName, ConversationMessage message) {
        String safeSkillName = requireNonBlank(skillName, "Skill name must not be blank.");
        if (message == null) {
            throw new IllegalArgumentException("Activated Skill message must not be null.");
        }

        Map<String, ConversationMessage> nextMessages = new LinkedHashMap<>(activatedMessagesBySkillName);
        nextMessages.put(safeSkillName, message);
        return new ActivatedSkillSet(nextMessages);
    }

    private static String extractSkillName(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }

        List<String> lines = content.lines().toList();
        if (lines.size() < 2) {
            return null;
        }
        if (!ACTIVATED_SKILL_HEADER.equals(lines.get(0).strip())) {
            return null;
        }

        // 第一行固定是 Activated Skill 头，
        // 第二行固定是 `name: <skillName>`，
        // 这里逐步校验格式，避免把普通 system 消息误判成激活 Skill。
        String nameLine = lines.get(1).strip();
        if (!nameLine.startsWith(NAME_PREFIX)) {
            return null;
        }

        String skillName = nameLine.substring(NAME_PREFIX.length()).strip();
        if (skillName.isEmpty()) {
            return null;
        }
        return skillName;
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
