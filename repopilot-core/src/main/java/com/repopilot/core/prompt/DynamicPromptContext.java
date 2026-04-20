package com.repopilot.core.prompt;

import com.repopilot.core.agent.AgentRunMode;
import com.repopilot.core.skill.SkillSummary;
import com.repopilot.core.tool.ToolDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 动态 prompt 输入上下文。
 * 这部分内容会随着 session、工作区和工具子集变化，
 * 因此必须和稳定的基础指令分开建模。
 */
public record DynamicPromptContext(
        String sessionPreamble,
        String workspaceContext,
        List<SkillSummary> skillSummaries,
        String budgetHint,
        List<ToolDefinition> availableTools,
        Map<String, String> runtimeMetadata,
        AgentRunMode runMode
) {

    public DynamicPromptContext(
            String sessionPreamble,
            String workspaceContext,
            List<SkillSummary> skillSummaries,
            String budgetHint,
            List<ToolDefinition> availableTools,
            Map<String, String> runtimeMetadata
    ) {
        this(
                sessionPreamble,
                workspaceContext,
                skillSummaries,
                budgetHint,
                availableTools,
                runtimeMetadata,
                AgentRunMode.EXECUTE
        );
    }

    public DynamicPromptContext {
        sessionPreamble = normalizeOptionalText(sessionPreamble);
        workspaceContext = normalizeOptionalText(workspaceContext);
        skillSummaries = normalizeSkillSummaries(skillSummaries);
        budgetHint = normalizeOptionalText(budgetHint);
        availableTools = normalizeAvailableTools(availableTools);
        runtimeMetadata = normalizeRuntimeMetadata(runtimeMetadata);
        runMode = Objects.requireNonNull(runMode, "runMode must not be null.");
    }

    private static String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private static List<SkillSummary> normalizeSkillSummaries(List<SkillSummary> skillSummaries) {
        if (skillSummaries == null) {
            return List.of();
        }

        List<SkillSummary> normalized = new ArrayList<>(skillSummaries.size());
        for (SkillSummary skillSummary : skillSummaries) {
            normalized.add(Objects.requireNonNull(skillSummary, "Skill summary must not be null."));
        }
        return List.copyOf(normalized);
    }

    private static List<ToolDefinition> normalizeAvailableTools(List<ToolDefinition> availableTools) {
        if (availableTools == null) {
            return List.of();
        }

        List<ToolDefinition> normalized = new ArrayList<>(availableTools.size());
        for (ToolDefinition toolDefinition : availableTools) {
            Objects.requireNonNull(toolDefinition, "Tool definition must not be null.");

            // 这里显式校验工具定义的关键字段，
            // 避免把脏数据静默带进动态 prompt，污染后续模型行为。
            normalized.add(new ToolDefinition(
                    normalizeRequiredText(toolDefinition.name(), "Tool name must not be blank."),
                    normalizeRequiredText(toolDefinition.description(), "Tool description must not be blank."),
                    toolDefinition.parametersSchema()
            ));
        }
        return List.copyOf(normalized);
    }

    private static Map<String, String> normalizeRuntimeMetadata(Map<String, String> runtimeMetadata) {
        if (runtimeMetadata == null || runtimeMetadata.isEmpty()) {
            return Map.of();
        }

        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : runtimeMetadata.entrySet()) {
            String key = normalizeRequiredText(entry.getKey(), "Runtime metadata key must not be blank.");
            String value = normalizeRequiredText(entry.getValue(), "Runtime metadata value must not be blank.");
            normalized.put(key, value);
        }
        return Collections.unmodifiableMap(normalized);
    }

    private static String normalizeRequiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
