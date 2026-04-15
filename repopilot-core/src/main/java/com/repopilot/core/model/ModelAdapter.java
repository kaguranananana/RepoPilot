package com.repopilot.core.model;

import java.util.List;

/**
 * 模型适配器接口。
 * 不管后面接 OpenAI、Anthropic 还是兼容接口，
 * core 层只关心“给一组消息，返回一个结构化模型输出”。
 */
public interface ModelAdapter {

    ModelResponse next(List<ConversationMessage> messages);
}

