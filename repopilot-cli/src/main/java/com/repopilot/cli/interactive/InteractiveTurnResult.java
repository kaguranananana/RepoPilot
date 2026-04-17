package com.repopilot.cli.interactive;

import com.repopilot.core.model.ConversationMessage;
import java.util.List;

/**
 * 单轮交互执行结果。
 * 它同时返回新的消息历史和最终回答，
 * 让上层 REPL 可以把历史继续带入下一轮。
 */
public record InteractiveTurnResult(
        List<ConversationMessage> messages,
        String finalAnswer
) {

    public InteractiveTurnResult {
        messages = List.copyOf(messages);
    }
}
