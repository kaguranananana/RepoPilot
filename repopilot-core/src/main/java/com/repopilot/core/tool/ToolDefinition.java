package com.repopilot.core.tool;

import java.util.Map;
import java.util.Objects;

/**
 * 工具对外暴露的定义信息。
 * 运行时把 name 和 description 给模型看，
 * 真正执行时再通过 ToolRegistry 找到对应的 handler。
 */
public record ToolDefinition(
        String name,
        String description,
        Map<String, Object> parametersSchema
) {

    public ToolDefinition {
        name = Objects.requireNonNull(name, "name must not be null.");
        description = Objects.requireNonNull(description, "description must not be null.");
        parametersSchema = parametersSchema == null ? Map.of() : Map.copyOf(parametersSchema);
    }

    public ToolDefinition(String name, String description) {
        this(name, description, Map.of());
    }
}
