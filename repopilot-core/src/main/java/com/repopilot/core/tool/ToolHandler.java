package com.repopilot.core.tool;

import java.util.Map;

/**
 * 单个工具的执行函数式接口。
 */
@FunctionalInterface
public interface ToolHandler {

    ToolExecutionResult execute(Map<String, String> arguments);

    default ToolExecutionResult execute(ToolExecutionContext context, Map<String, String> arguments) {
        // 默认仍复用旧的单参数工具协议，
        // 让现有工具和测试先保持稳定；
        // 只有确实需要读取会话上下文的工具，才显式覆盖这个重载。
        return execute(arguments);
    }
}
