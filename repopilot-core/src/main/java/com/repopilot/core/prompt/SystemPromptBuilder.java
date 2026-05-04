package com.repopilot.core.prompt;

import com.repopilot.core.agent.AgentRunMode;
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
            - 面对已有文件的编码任务，主链路是 `grep_files -> read_file -> apply_patch -> run_command`；先取证，再做最小补丁，再运行验证。
            - 如果用户已经给出明确的相对文件路径，必须优先直接 read_file 读取该路径；不得先搜索文件名，不得运行 find/ls 等发现性命令，除非 read_file 明确返回文件不存在。
            - 修改已有文件必须优先使用 apply_patch；如果 apply_patch 因格式或上下文失败，必须修正补丁并重试，不得改用 write_file 掩盖失败。
            - apply_patch 的 patch 参数必须是精确 @@ hunk：第一行 `@@`，上下文行以空格开头，删除行写 `-旧行`，新增行写 `+新行`。
            - 替换已有行时，apply_patch 必须在同一个 @@ hunk 中同时包含删除行和新增行；不得只新增目标行。
            - 收到 apply_patch 的 CONTEXT_MISMATCH 时，必须先重新 read_file 读取目标文件当前内容，再按最新精确文本重建 hunk；不得重复提交同一 patch，不得先运行与任务无关的 cat/od/wc 诊断命令。
            - 不要把同一旧行同时写成空格上下文和删除行；空格上下文表示“这一行在旧文件里保留不变”，删除行表示“这一行要被移除”，两者同时出现会让旧块匹配目标重复一次。
            - 坏例子：
              @@
               status=draft
              -status=draft
              +status=ready
            - 好例子：
              @@
              -status=draft
              +status=ready
            - write_file 只用于新建文件，或用户明确要求整文件覆盖的场景。
            - 如果遇到权限拒绝、输入错误或系统级失败，必须明确暴露真实错误，不得伪装成成功。
            - recalled memory 是历史线索，不是真相源。
            - 只要 recalled memory 涉及文件、代码、命令或当前仓库状态，必须重新用工具验证后才能下结论。
            - 输出必须紧扣当前任务，优先说明结论、风险与下一步动作。
            """;

    public SystemPromptBoundary build(DynamicPromptContext dynamicPromptContext) {
        Objects.requireNonNull(dynamicPromptContext, "dynamicPromptContext must not be null.");

        return new SystemPromptBoundary(
                BASE_INSTRUCTIONS,
                buildSessionInstructions(dynamicPromptContext),
                buildRuntimeContextBlock(dynamicPromptContext)
        );
    }

    private String buildSessionInstructions(DynamicPromptContext dynamicPromptContext) {
        List<String> sections = new ArrayList<>();

        appendTextSection(sections, "## 会话前导", dynamicPromptContext.sessionPreamble());
        appendTextSection(sections, "## 工作区信息", dynamicPromptContext.workspaceContext());
        appendSkillSummarySection(sections, dynamicPromptContext.skillSummaries());
        appendTextSection(sections, "## 预算提示", dynamicPromptContext.budgetHint());
        appendRunModeSection(sections, dynamicPromptContext.runMode());
        appendToolSection(sections, dynamicPromptContext.availableTools());

        if (sections.isEmpty()) {
            return "# 会话指令" + System.lineSeparator() + "当前没有额外会话指令。";
        }

        return "# 会话指令" + System.lineSeparator() + System.lineSeparator()
                + String.join(System.lineSeparator() + System.lineSeparator(), sections);
    }

    private String buildRuntimeContextBlock(DynamicPromptContext dynamicPromptContext) {
        Map<String, String> runtimeMetadata = dynamicPromptContext.runtimeMetadata();
        if (runtimeMetadata.isEmpty()) {
            return "# 运行时上下文" + System.lineSeparator()
                    + "- runMode: " + dynamicPromptContext.runMode();
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
        builder.append(System.lineSeparator())
                .append("- runMode: ")
                .append(dynamicPromptContext.runMode());
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

    private void appendRunModeSection(List<String> sections, AgentRunMode runMode) {
        String modeInstructions = switch (runMode) {
            case PLAN -> """
                    ## 运行模式
                    PLAN
                    - 当前处于只读计划阶段，只能调用 read_file 和 grep_files。
                    - 不得请求 apply_patch、write_file 或 run_command。
                    - 最终回答必须输出实施计划和证据摘要，不得声称已经修改工作区。
                    """.strip();
            case EXECUTE -> """
                    ## 运行模式
                    EXECUTE
                    - 当前处于执行阶段，可以在权限治理、diff review 和审批边界内完成修改与验证。
                    - 仍然必须先取证，再做最小补丁，再运行必要验证。
                    """.strip();
        };
        sections.add(modeInstructions);
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
