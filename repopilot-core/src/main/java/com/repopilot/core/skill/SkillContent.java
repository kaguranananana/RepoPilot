package com.repopilot.core.skill;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 按名称命中后再加载的 Skill 正文。
 * 这里不提前展开附件内容，
 * 只保留正文和附件解析所需的目录边界。
 */
public record SkillContent(
        SkillDescriptor descriptor,
        String body
) {

    public SkillContent {
        descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null.");
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Skill body must not be blank.");
        }
        body = body.strip();
    }

    public Path rootDirectory() {
        return descriptor.rootDirectory();
    }
}
