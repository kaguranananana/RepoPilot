package com.repopilot.cli.runtime;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.tool.ToolDefinition;
import java.util.List;

/**
 * 本地输入 token 估算器。
 * 调用方必须显式提供实现，context-cost 不做字符数兜底估算。
 */
@FunctionalInterface
public interface ModelInputTokenEstimator {

    int estimateInputTokens(List<ConversationMessage> messages, List<ToolDefinition> availableTools);
}
