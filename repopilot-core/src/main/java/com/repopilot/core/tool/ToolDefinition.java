package com.repopilot.core.tool;

/**
 * 工具对外暴露的定义信息。
 * 运行时把 name 和 description 给模型看，
 * 真正执行时再通过 ToolRegistry 找到对应的 handler。
 */
public record ToolDefinition(
        String name,
        String description
) {
}

