package com.repopilot.cli.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.tool.ToolDefinition;
import com.repopilot.protocol.json.ProtocolObjectMapperFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 JTokkit 的本地输入 token 估算器。
 * 只接受显式 encoding 名称，不做字符长度兜底估算。
 */
public final class JTokkitModelInputTokenEstimator implements ModelInputTokenEstimator {

    private static final EncodingRegistry ENCODING_REGISTRY = Encodings.newLazyEncodingRegistry();

    private final Encoding encoding;
    private final ObjectMapper objectMapper;

    public JTokkitModelInputTokenEstimator(String encodingName) {
        this(encodingName, ProtocolObjectMapperFactory.create());
    }

    JTokkitModelInputTokenEstimator(String encodingName, ObjectMapper objectMapper) {
        this.encoding = ENCODING_REGISTRY.getEncoding(requireNonBlank(encodingName, "encodingName must not be blank."))
                .orElseThrow(() -> new IllegalArgumentException("Unsupported token encoding: " + encodingName));
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null.");
    }

    @Override
    public int estimateInputTokens(List<ConversationMessage> messages, List<ToolDefinition> availableTools) {
        Objects.requireNonNull(messages, "messages must not be null.");
        Objects.requireNonNull(availableTools, "availableTools must not be null.");

        // 这里把模型输入渲染成稳定 JSON，再用同一个 encoding 计数。
        // 它不是 provider 账单事实来源，但能公平比较不同上下文策略的相对输入规模。
        return encoding.countTokensOrdinary(renderComparableRequest(messages, availableTools));
    }

    private String renderComparableRequest(List<ConversationMessage> messages, List<ToolDefinition> availableTools) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", messages.stream().map(this::renderMessage).toList());
        request.put("tools", availableTools.stream().map(this::renderTool).toList());
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("本地 token 估算输入序列化失败: " + exception.getMessage(), exception);
        }
    }

    private Map<String, Object> renderMessage(ConversationMessage message) {
        Map<String, Object> renderedMessage = new LinkedHashMap<>();
        renderedMessage.put("role", message.role().name());
        renderedMessage.put("content", message.content());
        if (message.toolCallId() != null && !message.toolCallId().isBlank()) {
            renderedMessage.put("toolCallId", message.toolCallId());
        }
        if (!message.toolCalls().isEmpty()) {
            renderedMessage.put("toolCalls", message.toolCalls().stream().map(this::renderToolCall).toList());
        }
        return renderedMessage;
    }

    private Map<String, Object> renderToolCall(ToolCall toolCall) {
        Map<String, Object> renderedToolCall = new LinkedHashMap<>();
        renderedToolCall.put("id", toolCall.id());
        renderedToolCall.put("toolName", toolCall.toolName());
        renderedToolCall.put("arguments", toolCall.arguments());
        return renderedToolCall;
    }

    private Map<String, Object> renderTool(ToolDefinition toolDefinition) {
        Map<String, Object> renderedTool = new LinkedHashMap<>();
        renderedTool.put("name", toolDefinition.name());
        renderedTool.put("description", toolDefinition.description());
        renderedTool.put("parametersSchema", toolDefinition.parametersSchema());
        return renderedTool;
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
