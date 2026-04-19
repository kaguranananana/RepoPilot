package com.repopilot.core.skill;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Skill 元信息索引。
 * 当前索引只负责稳定排序、按名查找和暴露摘要，
 * 不负责正文和附件的实际读取。
 */
public final class SkillIndex {

    private final List<SkillDescriptor> descriptors;
    private final Map<String, SkillDescriptor> descriptorsByName;

    public SkillIndex(List<SkillDescriptor> descriptors) {
        if (descriptors == null) {
            throw new IllegalArgumentException("Skill descriptors must not be null.");
        }

        // 先按稳定规则排序，
        // 让 system prompt、测试输出和调试结果都保持一致。
        List<SkillDescriptor> sortedDescriptors = descriptors.stream()
                .sorted(Comparator
                        .comparing(SkillDescriptor::name)
                        .thenComparing(SkillDescriptor::source)
                        .thenComparing(descriptor -> descriptor.skillFile().toString()))
                .toList();

        Map<String, SkillDescriptor> index = new LinkedHashMap<>();
        for (SkillDescriptor descriptor : sortedDescriptors) {
            if (index.containsKey(descriptor.name())) {
                throw new IllegalArgumentException("检测到重复 Skill 名称: " + descriptor.name());
            }
            index.put(descriptor.name(), descriptor);
        }

        this.descriptors = List.copyOf(sortedDescriptors);
        this.descriptorsByName = Map.copyOf(index);
    }

    public List<SkillDescriptor> descriptors() {
        return descriptors;
    }

    public List<SkillSummary> summaries() {
        return descriptors.stream().map(SkillDescriptor::toSummary).toList();
    }

    public Optional<SkillDescriptor> findDescriptor(String skillName) {
        requireNonBlank(skillName, "Skill name must not be blank.");
        return Optional.ofNullable(descriptorsByName.get(skillName.strip()));
    }

    public SkillDescriptor requireDescriptor(String skillName) {
        return findDescriptor(skillName)
                .orElseThrow(() -> new IllegalArgumentException("未找到 Skill: " + skillName.strip()));
    }

    /**
     * 为后续 `global policy ∩ skill allowed-tools` 预留显式查询入口。
     */
    public List<String> allowedToolsFor(String skillName) {
        return requireDescriptor(skillName).allowedTools();
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
