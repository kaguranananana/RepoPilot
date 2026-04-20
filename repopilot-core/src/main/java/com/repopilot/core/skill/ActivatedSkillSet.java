package com.repopilot.core.skill;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import java.util.ArrayList;
import java.util.Collections;
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
    private static final String ALLOWED_TOOLS_PREFIX = "allowed-tools:";

    private final Map<String, ActivatedSkill> activatedSkillsBySkillName;

    private ActivatedSkillSet(Map<String, ActivatedSkill> activatedSkillsBySkillName) {
        this.activatedSkillsBySkillName = Collections.unmodifiableMap(new LinkedHashMap<>(activatedSkillsBySkillName));
    }

    public static ActivatedSkillSet empty() {
        return new ActivatedSkillSet(Map.of());
    }

    public static ActivatedSkillSet fromMessages(List<ConversationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return empty();
        }

        Map<String, ActivatedSkill> activatedSkills = new LinkedHashMap<>();
        for (ConversationMessage message : messages) {
            if (message.role() != MessageRole.SYSTEM) {
                continue;
            }

            ParsedActivatedSkill parsedSkill = parseActivatedSkill(message.content());
            if (parsedSkill == null) {
                continue;
            }

            // 这里逐条从历史消息重建已激活状态，
            // 这样无论消息来自用户显式激活还是模型工具调用，
            // 后续重复激活判断都只依赖统一的历史事实。
            activatedSkills.putIfAbsent(
                    parsedSkill.skillName(),
                    new ActivatedSkill(message, parsedSkill.allowedTools())
            );
        }
        return new ActivatedSkillSet(activatedSkills);
    }

    public boolean contains(String skillName) {
        String safeSkillName = requireNonBlank(skillName, "Skill name must not be blank.");
        return activatedSkillsBySkillName.containsKey(safeSkillName);
    }

    public ActivatedSkillSet append(String skillName, ConversationMessage message) {
        String safeSkillName = requireNonBlank(skillName, "Skill name must not be blank.");
        if (message == null) {
            throw new IllegalArgumentException("Activated Skill message must not be null.");
        }

        ParsedActivatedSkill parsedSkill = requireParsedActivatedSkill(message);
        if (!safeSkillName.equals(parsedSkill.skillName())) {
            throw new IllegalArgumentException("Activated Skill message 与目标 Skill 名称不一致: " + safeSkillName);
        }

        Map<String, ActivatedSkill> nextSkills = new LinkedHashMap<>(activatedSkillsBySkillName);
        nextSkills.put(safeSkillName, new ActivatedSkill(message, parsedSkill.allowedTools()));
        return new ActivatedSkillSet(nextSkills);
    }

    public List<String> resolveEffectiveAllowedTools(List<String> globalToolNames) {
        // 先把全局工具名做一次刚性归一化，
        // 保证后续交集运算面对的是非空、去掉首尾空白的稳定输入。
        List<String> normalizedGlobalToolNames = normalizeGlobalToolNames(globalToolNames);
        // 初始有效集合就是全局工具集合，
        // 后续只会在它的基础上不断收缩，不会凭空放大。
        List<String> effectiveToolNames = List.copyOf(normalizedGlobalToolNames);
        // 只有至少一个已激活 Skill 显式声明了 allowed-tools，
        // 才说明当前会话真的进入了 Skill 工具约束模式。
        boolean constrained = false;

        for (ActivatedSkill activatedSkill : activatedSkillsBySkillName.values()) {
            if (activatedSkill.allowedTools().isEmpty()) {
                continue;
            }

            constrained = true;
            // 每遇到一个带 allowed-tools 的 Skill，
            // 就把当前集合继续与该 Skill 做交集，
            // 直到得到最终有效工具子集。
            effectiveToolNames = effectiveToolNames.stream()
                    .filter(toolName -> activatedSkill.allowedTools().contains(toolName))
                    .toList();
        }

        if (!constrained) {
            return List.copyOf(normalizedGlobalToolNames);
        }
        return List.copyOf(effectiveToolNames);
    }

    public List<String> constrainedSkillNames() {
        List<String> constrainedSkillNames = new ArrayList<>();
        for (Map.Entry<String, ActivatedSkill> entry : activatedSkillsBySkillName.entrySet()) {
            // 只把真正声明了 allowed-tools 的 Skill 记为约束来源，
            // 否则错误信息会把“未收缩工具集的 Skill”也混进去。
            if (!entry.getValue().allowedTools().isEmpty()) {
                constrainedSkillNames.add(entry.getKey());
            }
        }
        return List.copyOf(constrainedSkillNames);
    }

    private static ParsedActivatedSkill parseActivatedSkill(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }

        // 激活消息至少要有标题和一行元信息，
        // 否则不可能是受支持的 Activated Skill 格式。
        List<String> lines = content.lines().toList();
        if (lines.size() < 2) {
            return null;
        }
        // 第一行不是固定标题时直接退出，
        // 避免把普通 system prompt 或上下文消息误判成 Skill 激活记录。
        if (!ACTIVATED_SKILL_HEADER.equals(lines.get(0).strip())) {
            return null;
        }

        String skillName = null;
        List<String> allowedTools = List.of();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index).strip();
            // 遇到第一段空行就停止，
            // 只解析头部元信息，不把正文里的普通文本当成字段。
            if (line.isEmpty()) {
                break;
            }

            if (line.startsWith(NAME_PREFIX)) {
                // `name:` 是激活记录的主键字段，
                // 缺失或空值都说明格式不成立。
                skillName = line.substring(NAME_PREFIX.length()).strip();
                if (skillName.isEmpty()) {
                    return null;
                }
                continue;
            }
            if (line.startsWith(ALLOWED_TOOLS_PREFIX)) {
                // `allowed-tools:` 允许为空，
                // 空值语义是“这个 Skill 不额外收缩工具集”。
                allowedTools = parseAllowedToolsLine(line.substring(ALLOWED_TOOLS_PREFIX.length()));
            }
        }
        if (skillName == null || skillName.isEmpty()) {
            return null;
        }
        return new ParsedActivatedSkill(skillName, allowedTools);
    }

    private static ParsedActivatedSkill requireParsedActivatedSkill(ConversationMessage message) {
        ParsedActivatedSkill parsedSkill = parseActivatedSkill(message.content());
        if (parsedSkill == null) {
            throw new IllegalArgumentException("Activated Skill message 格式非法。");
        }
        return parsedSkill;
    }

    private static List<String> parseAllowedToolsLine(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return List.of();
        }

        List<String> allowedTools = new ArrayList<>();
        for (String token : rawValue.split(",")) {
            String safeAllowedTool = requireNonBlank(token, "Activated Skill allowed-tool must not be blank.");
            // 这里直接拒绝重复工具名，
            // 避免后续做多 Skill 交集时还要猜重复项是否有特殊语义。
            if (allowedTools.contains(safeAllowedTool)) {
                throw new IllegalArgumentException("重复的 Activated Skill allowed-tool: " + safeAllowedTool);
            }
            allowedTools.add(safeAllowedTool);
        }
        return List.copyOf(allowedTools);
    }

    private static List<String> normalizeGlobalToolNames(List<String> globalToolNames) {
        if (globalToolNames == null) {
            throw new IllegalArgumentException("Global tool names must not be null.");
        }

        List<String> normalized = new ArrayList<>(globalToolNames.size());
        for (String globalToolName : globalToolNames) {
            // 这里不做任何猜测式兼容，
            // 只接受显式非空工具名，非法输入直接暴露错误。
            normalized.add(requireNonBlank(globalToolName, "Global tool name must not be blank."));
        }
        return List.copyOf(normalized);
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    private record ActivatedSkill(
            ConversationMessage message,
            List<String> allowedTools
    ) {

        private ActivatedSkill {
            if (message == null) {
                throw new IllegalArgumentException("Activated Skill message must not be null.");
            }
            allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        }
    }

    private record ParsedActivatedSkill(
            String skillName,
            List<String> allowedTools
    ) {

        private ParsedActivatedSkill {
            skillName = requireNonBlank(skillName, "Parsed Skill name must not be blank.");
            allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        }
    }
}
