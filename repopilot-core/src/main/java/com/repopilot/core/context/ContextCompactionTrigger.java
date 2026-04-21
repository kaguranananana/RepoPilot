package com.repopilot.core.context;

/**
 * 上下文压缩触发原因。
 * 枚举值同时服务 trace 记录和 working memory 归档原因。
 */
public enum ContextCompactionTrigger {

    HIGH_FIDELITY_MESSAGE_LIMIT("high_fidelity_message_limit"),
    TOKEN_BUDGET("token_budget");

    private final String archiveReason;

    ContextCompactionTrigger(String archiveReason) {
        this.archiveReason = archiveReason;
    }

    public String archiveReason() {
        return archiveReason;
    }
}
