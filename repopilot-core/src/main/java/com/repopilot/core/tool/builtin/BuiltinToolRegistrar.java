package com.repopilot.core.tool.builtin;

import com.repopilot.core.skill.SkillLoader;
import com.repopilot.core.tool.ToolRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 统一注册当前阶段内置工具。
 * 这里显式固定注册顺序，
 * 避免后续 prompt 中的工具清单因为输出顺序抖动而影响模型行为。
 */
public final class BuiltinToolRegistrar {

    private BuiltinToolRegistrar() {
    }

    public static void registerAll(ToolRegistry toolRegistry, Path workspaceRoot, SkillLoader skillLoader) {
        Objects.requireNonNull(toolRegistry, "toolRegistry must not be null.");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null.");
        Objects.requireNonNull(skillLoader, "skillLoader must not be null.");

        // 先注册读文件工具，
        // 让模型能够拿到最直接的源码证据。
        toolRegistry.register(
                "read_file",
                "读取单个 UTF-8 文本文件内容",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of(
                                        "type", "string",
                                        "description", "要读取的文件路径"
                                )
                        ),
                        "required", List.of("path")
                ),
                new ReadFileTool(workspaceRoot)
        );

        // 再注册 grep 工具，
        // 让模型可以先缩小范围，再决定读取哪些文件。
        toolRegistry.register(
                "grep_files",
                "按正则表达式递归搜索文件内容",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "pattern", Map.of(
                                        "type", "string",
                                        "description", "要搜索的正则表达式"
                                ),
                                "path", Map.of(
                                        "type", "string",
                                        "description", "可选，限定搜索的文件或目录"
                                )
                        ),
                        "required", List.of("pattern")
                ),
                new GrepFilesTool(workspaceRoot)
        );

        // Skill 激活属于显式上下文装配动作，
        // 风险明显低于写文件和跑命令，
        // 因此固定放在变更型工具之前暴露给模型。
        toolRegistry.register(
                "activate_skill",
                "按名称激活单个 Skill",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of(
                                        "type", "string",
                                        "description", "要激活的 Skill 名称"
                                )
                        ),
                        "required", List.of("name")
                ),
                new ActivateSkillTool(skillLoader)
        );

        // 补丁式编辑优先于整文件覆盖暴露，
        // 让编码任务主链路默认倾向于最小修改。
        toolRegistry.register(
                "apply_patch",
                "对已有单个 UTF-8 文本文件应用精确 @@ hunk 补丁；patch 第一行必须是 @@，上下文行以空格开头，删除行写 -旧行，新增行写 +新行",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of(
                                        "type", "string",
                                        "description", "要修改的工作区文件路径"
                                ),
                                "patch", Map.of(
                                        "type", "string",
                                        "description", "严格补丁文本，示例: @@\\n 上下文行\\n-旧行\\n+新行。不要包裹 Markdown 代码块，不要添加文件路径头。"
                                )
                        ),
                        "required", List.of("path", "patch")
                ),
                new ApplyPatchTool(workspaceRoot)
        );

        // 再注册写文件工具，
        // 让模型在确实需要整文件产出时仍可走审批后的写入能力。
        toolRegistry.register(
                "write_file",
                "向单个 UTF-8 文本文件写入完整内容；仅用于新建文件或用户明确要求整文件覆盖，修改已有文件应优先使用 apply_patch",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of(
                                        "type", "string",
                                        "description", "要写入的文件路径"
                                ),
                                "content", Map.of(
                                        "type", "string",
                                        "description", "要写入文件的完整文本内容"
                                )
                        ),
                        "required", List.of("path", "content")
                ),
                new WriteFileTool(workspaceRoot)
        );

        // 最后注册命令工具，
        // 让模型在需要时读取构建、测试和系统命令的真实结果。
        toolRegistry.register(
                "run_command",
                "在工作区目录内执行单条 shell 命令",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "command", Map.of(
                                        "type", "string",
                                        "description", "要执行的 shell 命令"
                                )
                        ),
                        "required", List.of("command")
                ),
                new RunCommandTool(workspaceRoot)
        );
    }
}
