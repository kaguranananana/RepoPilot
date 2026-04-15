package com.repopilot.core.tool;

/**
 * 请求执行不存在工具时抛出的异常。
 */
public class ToolNotFoundException extends RuntimeException {

    public ToolNotFoundException(String toolName) {
        super("Tool not found: " + toolName);
    }
}

