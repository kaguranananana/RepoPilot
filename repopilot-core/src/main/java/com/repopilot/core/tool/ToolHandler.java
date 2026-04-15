package com.repopilot.core.tool;

import java.util.Map;

/**
 * 单个工具的执行函数式接口。
 */
@FunctionalInterface
public interface ToolHandler {

    ToolExecutionResult execute(Map<String, String> arguments);
}

