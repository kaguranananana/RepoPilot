package com.repopilot.core.model;

/**
 * 模型单轮输出的统一抽象。
 * 当前只有两种合法结果：
 * 1. 直接给最终回答
 * 2. 发起一个或多个工具调用
 */
public sealed interface ModelResponse permits FinalModelResponse, ToolCallModelResponse {
}

