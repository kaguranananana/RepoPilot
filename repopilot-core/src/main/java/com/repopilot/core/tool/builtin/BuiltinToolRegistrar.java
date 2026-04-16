package com.repopilot.core.tool.builtin;

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

    public static void registerAll(ToolRegistry toolRegistry, Path workspaceRoot) {
        Objects.requireNonNull(toolRegistry, "toolRegistry must not be null.");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null.");

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
