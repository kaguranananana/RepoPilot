package com.repopilot.core.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 运行时工具注册中心。
 * 当前职责非常单一：
 * 1. 注册工具定义和处理器
 * 2. 按名称执行工具
 * 3. 向外暴露可用工具列表
 */
public class ToolRegistry {

    private final Map<String, RegisteredTool> tools = new LinkedHashMap<>();

    public synchronized void register(String name, String description, ToolHandler handler) {
        register(name, description, Map.of(), handler);
    }

    public synchronized void register(
            String name,
            String description,
            Map<String, Object> parametersSchema,
            ToolHandler handler
    ) {
        requireNonBlank(name, "Tool name must not be blank.");
        requireNonBlank(description, "Tool description must not be blank.");
        Objects.requireNonNull(parametersSchema, "Tool parameters schema must not be null.");
        Objects.requireNonNull(handler, "Tool handler must not be null.");

        if (tools.containsKey(name)) {
            throw new IllegalArgumentException("Tool already registered: " + name);
        }

        tools.put(name, new RegisteredTool(new ToolDefinition(name, description, parametersSchema), handler));
    }

    public synchronized List<ToolDefinition> list() {
        return tools.values().stream()
                .map(RegisteredTool::definition)
                .toList();
    }

    public synchronized ToolExecutionResult execute(String toolName, Map<String, String> arguments) {
        RegisteredTool tool = tools.get(toolName);
        if (tool == null) {
            throw new ToolNotFoundException(toolName);
        }

        Map<String, String> safeArguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        return tool.handler().execute(safeArguments);
    }

    private void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private record RegisteredTool(
            ToolDefinition definition,
            ToolHandler handler
    ) {
    }
}
