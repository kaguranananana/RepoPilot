package com.repopilot.cli.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import com.repopilot.core.tool.ToolDefinition;
import com.repopilot.protocol.json.ProtocolObjectMapperFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OpenAI 兼容非流式聊天模型适配器。
 * 当前版本只覆盖最小真实主链路：
 * 1. 把 RepoPilot 消息映射成 OpenAI 兼容 messages
 * 2. 调用 OpenAI 兼容 `/chat/completions`
 * 3. 解析 assistant 文本回答或 `tool_calls`
 * 4. 在下一轮把 `assistant(tool_calls) -> tool(tool_call_id)` 正确回注给模型
 */
public final class OpenAiCompatibleChatModelAdapter implements ModelAdapter {

    private final String apiKey;
    private final String baseUrl;
    private final String modelName;
    private final List<ToolDefinition> availableTools;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleChatModelAdapter(
            String apiKey,
            String baseUrl,
            String modelName,
            List<ToolDefinition> availableTools
    ) {
        this(
                apiKey,
                baseUrl,
                modelName,
                availableTools,
                HttpClient.newHttpClient(),
                ProtocolObjectMapperFactory.create()
        );
    }

    OpenAiCompatibleChatModelAdapter(
            String apiKey,
            String baseUrl,
            String modelName,
            List<ToolDefinition> availableTools,
            HttpClient httpClient,
            ObjectMapper objectMapper
    ) {
        this.apiKey = requireNonBlank(apiKey, "apiKey must not be blank.");
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.modelName = requireNonBlank(modelName, "modelName must not be blank.");
        this.availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null.");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null.");
    }

    @Override
    public ModelResponse next(List<ConversationMessage> messages) {
        Objects.requireNonNull(messages, "messages must not be null.");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(messages)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseResponse(response);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to call OpenAI-compatible API: " + exception.getMessage(),
                    exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI-compatible API call was interrupted.", exception);
        }
    }

    private String buildRequestBody(List<ConversationMessage> messages) throws IOException {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", modelName);
        requestBody.put("stream", false);
        requestBody.put("messages", mapMessages(messages));
        if (!availableTools.isEmpty()) {
            requestBody.put("tools", mapTools());
        }
        return objectMapper.writeValueAsString(requestBody);
    }

    private List<Map<String, Object>> mapMessages(List<ConversationMessage> messages) {
        List<Map<String, Object>> apiMessages = new ArrayList<>(messages.size());

        for (ConversationMessage message : messages) {
            Objects.requireNonNull(message, "message must not be null.");

            // 这里逐条做显式 role 映射，
            // 让“RepoPilot 内部消息角色”和“OpenAI 兼容接口角色”之间的边界保持可审计。
            Map<String, Object> apiMessage = new LinkedHashMap<>();

            switch (message.role()) {
                case SYSTEM -> {
                    apiMessage.put("role", "system");
                    apiMessage.put("content", requireNonBlank(message.content(), "message content must not be blank."));
                }
                case WORKING_MEMORY, CONTEXT_SUMMARY -> {
                    // OpenAI 兼容接口并不认识 RepoPilot 的内部消息角色，
                    // 所以这里显式把结构化上下文映射成 system 消息。
                    // 这样它们仍然保持“运行时注入上下文”的语义，
                    // 同时不会被伪装成用户真实输入。
                    apiMessage.put("role", "system");
                    apiMessage.put("content", requireNonBlank(message.content(), "message content must not be blank."));
                }
                case USER -> {
                    apiMessage.put("role", "user");
                    apiMessage.put("content", requireNonBlank(message.content(), "message content must not be blank."));
                }
                case ASSISTANT -> {
                    apiMessage.put("role", "assistant");
                    apiMessage.put("content", message.content());
                    if (!message.toolCalls().isEmpty()) {
                        apiMessage.put("tool_calls", mapAssistantToolCalls(message.toolCalls()));
                    }
                }
                case TOOL -> {
                    apiMessage.put("role", "tool");
                    apiMessage.put("tool_call_id", message.toolCallId());
                    apiMessage.put("content", requireNonBlank(message.content(), "message content must not be blank."));
                }
            }

            apiMessages.add(apiMessage);
        }

        return List.copyOf(apiMessages);
    }

    private List<Map<String, Object>> mapAssistantToolCalls(List<ToolCall> toolCalls) {
        List<Map<String, Object>> apiToolCalls = new ArrayList<>(toolCalls.size());

        for (ToolCall toolCall : toolCalls) {
            Map<String, Object> apiToolCall = new LinkedHashMap<>();
            apiToolCall.put("id", toolCall.id());
            apiToolCall.put("type", "function");

            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", toolCall.toolName());
            function.put("arguments", serializeArguments(toolCall.arguments()));
            apiToolCall.put("function", function);
            apiToolCalls.add(apiToolCall);
        }

        return List.copyOf(apiToolCalls);
    }

    private List<Map<String, Object>> mapTools() {
        List<Map<String, Object>> tools = new ArrayList<>(availableTools.size());

        for (ToolDefinition toolDefinition : availableTools) {
            if (toolDefinition.parametersSchema().isEmpty()) {
                throw new IllegalStateException("Tool schema must not be empty: " + toolDefinition.name());
            }

            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");

            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", toolDefinition.name());
            function.put("description", toolDefinition.description());
            function.put("parameters", toolDefinition.parametersSchema());
            tool.put("function", function);
            tools.add(tool);
        }

        return List.copyOf(tools);
    }

    private ModelResponse parseResponse(HttpResponse<String> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "OpenAI-compatible API request failed with status %d: %s".formatted(
                            response.statusCode(),
                            response.body()
                    )
            );
        }

        JsonNode messageNode = objectMapper.readTree(response.body()).path("choices").path(0).path("message");
        JsonNode toolCallsNode = messageNode.path("tool_calls");
        if (toolCallsNode.isArray() && toolCallsNode.size() > 0) {
            return new ToolCallModelResponse(parseToolCalls(toolCallsNode));
        }

        JsonNode contentNode = messageNode.path("content");
        if (!contentNode.isTextual() || contentNode.asText().isBlank()) {
            throw new IllegalStateException("OpenAI-compatible API response does not contain assistant content.");
        }

        return new FinalModelResponse(contentNode.asText().strip());
    }

    private List<ToolCall> parseToolCalls(JsonNode toolCallsNode) throws IOException {
        List<ToolCall> toolCalls = new ArrayList<>(toolCallsNode.size());

        for (JsonNode toolCallNode : toolCallsNode) {
            String id = readRequiredText(toolCallNode, "id");
            JsonNode functionNode = toolCallNode.path("function");
            String toolName = readRequiredText(functionNode, "name");
            String argumentsJson = readRequiredText(functionNode, "arguments");
            toolCalls.add(new ToolCall(id, toolName, parseArguments(argumentsJson)));
        }

        return List.copyOf(toolCalls);
    }

    private Map<String, String> parseArguments(String argumentsJson) throws IOException {
        JsonNode argumentsNode = objectMapper.readTree(argumentsJson);
        if (!argumentsNode.isObject()) {
            throw new IllegalStateException("Tool call arguments must be a JSON object.");
        }

        Map<String, String> arguments = new LinkedHashMap<>();
        argumentsNode.fields().forEachRemaining(entry -> {
            JsonNode valueNode = entry.getValue();
            if (!valueNode.isTextual()) {
                throw new IllegalStateException("Tool argument must be a string: " + entry.getKey());
            }
            arguments.put(entry.getKey(), valueNode.asText());
        });
        return Map.copyOf(arguments);
    }

    private String serializeArguments(Map<String, String> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize tool arguments.", exception);
        }
    }

    private String readRequiredText(JsonNode node, String fieldName) {
        JsonNode fieldNode = node.path(fieldName);
        if (!fieldNode.isTextual() || fieldNode.asText().isBlank()) {
            throw new IllegalStateException("OpenAI-compatible tool call field is missing: " + fieldName);
        }
        return fieldNode.asText().strip();
    }

    private String normalizeBaseUrl(String value) {
        String normalized = requireNonBlank(value, "baseUrl must not be blank.");
        if (normalized.endsWith("/")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
