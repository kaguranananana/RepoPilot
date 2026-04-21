package com.repopilot.core.context;

import com.repopilot.core.model.ConversationMessage;
import java.util.List;

/**
 * AgentLoop 使用的输入 token 估算器。
 * core 只定义接口，具体 tokenizer 由 CLI 或运行时显式注入。
 */
@FunctionalInterface
public interface ContextInputTokenEstimator {

    int estimateInputTokens(List<ConversationMessage> messages);

    static ContextInputTokenEstimator unavailable() {
        return messages -> {
            throw new IllegalStateException("启用 token budget 压缩时必须显式提供输入 token 估算器。");
        };
    }
}
