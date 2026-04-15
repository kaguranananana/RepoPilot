package com.repopilot.core.model;

/**
 * 表示模型已经准备给出最终回答，不再请求工具。
 */
public record FinalModelResponse(String message) implements ModelResponse {
}

