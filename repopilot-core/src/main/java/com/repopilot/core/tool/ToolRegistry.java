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

    public void register(String name, String description, ToolHandler handler) {
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

    public List<ToolDefinition> list() {
        synchronized (this) {
            // ToolDefinition 的输出顺序必须稳定，
            // 因此这里要在锁内完成整次遍历和快照生成，
            // 避免 LinkedHashMap 在并发写入时让导出结果抖动。
            return tools.values().stream()
                    .map(RegisteredTool::definition)
                    .toList();
        }
    }

    public ToolDefinition requireDefinition(String toolName) {
        requireNonBlank(toolName, "Tool name must not be blank.");

        RegisteredTool tool;
        synchronized (this) {
            // 这里只需要在锁内把工具引用取出来，
            // 取到不可变 record 后就没必要继续占着 registry 的全局锁。
            tool = tools.get(toolName);
        }
        if (tool == null) {
            throw new ToolNotFoundException(toolName);
        }
        return tool.definition();
    }

    public ToolExecutionResult execute(String toolName, Map<String, String> arguments) {
        requireNonBlank(toolName, "Tool name must not be blank.");

        RegisteredTool tool;
        synchronized (this) {
            // execute 的锁只保护 registry 查询本身，
            // 真正的 handler 执行可能很慢，绝不能把整个执行过程都包进锁里，
            // 否则 list()/requireDefinition() 这类只读操作也会被无谓阻塞。
            tool = tools.get(toolName);
        }
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
