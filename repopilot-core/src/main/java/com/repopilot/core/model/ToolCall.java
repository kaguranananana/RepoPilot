package com.repopilot.core.model;

import java.util.Map;

/**
 * 模型请求调用工具时的结构化描述。
 * arguments 先收敛为字符串字典，方便协议简单可控，
 * 后续如果要支持复杂 JSON 参数，再把它升级为更通用的值模型。
 */
public record ToolCall(
        String id,
        String toolName,
        Map<String, String> arguments
) {

    public ToolCall {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}

