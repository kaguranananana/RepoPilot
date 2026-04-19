package com.repopilot.core.prompt;

import com.repopilot.core.skill.SkillSummary;
import com.repopilot.core.tool.ToolDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 负责把稳定基础指令、会话指令和 runtime metadata 组装成清晰边界。
 * 当前版本先把 prompt 分成三块：
 * 1. 基础指令
 * 2. 会话指令
 * 3. 独立 runtime context
 */
public class SystemPromptBuilder {

    private static final String BASE_INSTRUCTIONS = """
            # 基础指令
            - 你是 RepoPilot，一个面向本地代码仓研发任务的编码代理。
            - 你的判断必须基于真实工作区证据、工具结果和当前会话上下文，不得捏造事实。
            - 只能使用当前暴露的工具能力，并遵守权限、审查与治理边界。
            - 如果遇到权限拒绝、输入错误或系统级失败，必须明确暴露真实错误，不得伪装成成功。
            - 输出必须紧扣当前任务，优先说明结论、风险与下一步动作。
            """;

    public SystemPromptBoundary build(DynamicPromptContext dynamicPromptContext) {
        Objects.requireNonNull(dynamicPromptContext, "dynamicPromptContext must not be null.");

        return new SystemPromptBoundary(
                BASE_INSTRUCTIONS,
                buildSessionInstructions(dynamicPromptContext),
                buildRuntimeContextBlock(dynamicPromptContext.runtimeMetadata())
        );
    }

    private String buildSessionInstructions(DynamicPromptContext dynamicPromptContext) {
        List<String> sections = new ArrayList<>();

        appendTextSection(sections, "## 会话前导", dynamicPromptContext.sessionPreamble());
        appendTextSection(sections, "## 工作区信息", dynamicPromptContext.workspaceContext());
        appendSkillSummarySection(sections, dynamicPromptContext.skillSummaries());
        appendTextSection(sections, "## 预算提示", dynamicPromptContext.budgetHint());
        appendToolSection(sections, dynamicPromptContext.availableTools());

        if (sections.isEmpty()) {
            return "# 会话指令" + System.lineSeparator() + "当前没有额外会话指令。";
        }

        return "# 会话指令" + System.lineSeparator() + System.lineSeparator()
                + String.join(System.lineSeparator() + System.lineSeparator(), sections);
    }

    private String buildRuntimeContextBlock(Map<String, String> runtimeMetadata) {
        if (runtimeMetadata.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder("# 运行时上下文");
        for (Map.Entry<String, String> entry : runtimeMetadata.entrySet()) {
            // runtime metadata 必须保持在独立块中，
            // 这样 sessionId、当前时间等高频数据就不会污染稳定 system prompt。
            builder.append(System.lineSeparator())
                    .append("- ")
                    .append(entry.getKey())
                    .append(": ")
                    .append(entry.getValue());
        }
        return builder.toString();
    }

    private void appendTextSection(List<String> sections, String title, String value) {
        if (value == null) {
            return;
        }
        sections.add(title + System.lineSeparator() + value);
    }

    private void appendSkillSummarySection(List<String> sections, List<SkillSummary> skillSummaries) {
        if (skillSummaries.isEmpty()) {
            return;
        }

        StringBuilder builder = new StringBuilder("## Skill 摘要");
        for (SkillSummary skillSummary : skillSummaries) {
            // 默认 prompt 只看到稳定摘要，
            // allowed-tools 等约束字段保留在结构化对象里供后续治理链路使用。
            builder.append(System.lineSeparator())
                    .append("- ")
                    .append(skillSummary.toPromptLine());
        }
        sections.add(builder.toString());
    }

    private void appendToolSection(List<String> sections, List<ToolDefinition> availableTools) {
        if (availableTools.isEmpty()) {
            return;
        }

        StringBuilder builder = new StringBuilder("## 可用工具子集");
        for (ToolDefinition toolDefinition : availableTools) {
            // 工具子集单独成段，
            // 让模型看到“这轮到底允许用哪些工具”，同时不把信息混进基础指令。
            builder.append(System.lineSeparator())
                    .append("- ")
                    .append(toolDefinition.name())
                    .append(": ")
                    .append(toolDefinition.description());
        }
        sections.add(builder.toString());
    }
}
