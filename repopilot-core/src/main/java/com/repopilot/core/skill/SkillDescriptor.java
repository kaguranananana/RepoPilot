package com.repopilot.core.skill;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Skill 在索引阶段暴露的完整元信息。
 * 这里显式保留来源、目录、正文入口和 allowed-tools，
 * 供后续正文加载与工具约束模型继续复用。
 */
public record SkillDescriptor(
        String name,
        String summary,
        String source,
        Path rootDirectory,
        Path skillFile,
        List<String> allowedTools
) {

    public SkillDescriptor {
        name = requireNonBlank(name, "Skill name must not be blank.");
        summary = requireNonBlank(summary, "Skill summary must not be blank.");
        source = requireNonBlank(source, "Skill source must not be blank.");
        rootDirectory = normalizePath(rootDirectory, "Skill rootDirectory must not be null.");
        skillFile = normalizePath(skillFile, "Skill skillFile must not be null.");
        allowedTools = normalizeAllowedTools(allowedTools);

        // Skill 正文入口必须落在自己的目录内部，
        // 否则后续正文加载和附件解析都会失去边界。
        if (!skillFile.startsWith(rootDirectory)) {
            throw new IllegalArgumentException("Skill 文件必须位于 Skill 根目录内: " + skillFile);
        }
    }

    public SkillSummary toSummary() {
        return new SkillSummary(name, summary, source, allowedTools);
    }

    private static Path normalizePath(Path path, String message) {
        return Objects.requireNonNull(path, message).toAbsolutePath().normalize();
    }

    private static List<String> normalizeAllowedTools(List<String> allowedTools) {
        if (allowedTools == null) {
            return List.of();
        }

        List<String> normalized = new ArrayList<>(allowedTools.size());
        for (String allowedTool : allowedTools) {
            String safeAllowedTool = requireNonBlank(allowedTool, "Allowed tool must not be blank.");
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
