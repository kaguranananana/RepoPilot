package com.repopilot.core.skill;

import java.util.ArrayList;
import java.util.List;

/**
 * Skill 摘要信息。
 * 默认只把这部分稳定、短小的元信息暴露给 system prompt，
 * 从而避免把完整 Skill 正文一次性塞进上下文窗口。
 */
public record SkillSummary(
        String name,
        String summary,
        String source,
        List<String> allowedTools
) {

    public SkillSummary {
        name = requireNonBlank(name, "Skill name must not be blank.");
        summary = requireNonBlank(summary, "Skill summary must not be blank.");
        source = requireNonBlank(source, "Skill source must not be blank.");
        allowedTools = normalizeAllowedTools(allowedTools);
    }

    /**
     * prompt 默认只看到名称和摘要，
     * 不把 allowed-tools 等执行约束直接混进摘要文本。
     */
    public String toPromptLine() {
        return name + ": " + summary;
    }

    private static List<String> normalizeAllowedTools(List<String> allowedTools) {
        if (allowedTools == null) {
            return List.of();
        }

        List<String> normalized = new ArrayList<>(allowedTools.size());
        for (String allowedTool : allowedTools) {
            String safeAllowedTool = requireNonBlank(allowedTool, "Allowed tool must not be blank.");

            // 这里直接拒绝重复工具名，
            // 避免后续做 allowed-tools 交集时还要猜测重复项的语义。
            if (normalized.contains(safeAllowedTool)) {
                throw new IllegalArgumentException("重复的 Skill allowed-tool: " + safeAllowedTool);
            }
            normalized.add(safeAllowedTool);
        }
        return List.copyOf(normalized);
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
