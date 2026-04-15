package com.repopilot.core.model;

/**
 * 运行时内部统一使用的消息模型。
 * 当前先只保存 role 和 content，
 * 等后面接入 trace、toolUseId、审批元数据时再逐步扩展。
 */
public record ConversationMessage(
        MessageRole role,
        String content
) {
}

