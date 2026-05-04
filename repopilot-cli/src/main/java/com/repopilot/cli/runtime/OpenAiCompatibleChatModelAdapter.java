package com.repopilot.cli.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.TokenUsage;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import com.repopilot.core.skill.ActivatedSkillSet;
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
import java.util.Optional;
import java.util.Set;

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
    private final String forcedToolChoiceName;
    private final String thinkingModeType;
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
                null,
                null,
                HttpClient.newHttpClient(),
                ProtocolObjectMapperFactory.create()
        );
    }

    OpenAiCompatibleChatModelAdapter(
            String apiKey,
            String baseUrl,
            String modelName,
            List<ToolDefinition> availableTools,
            String forcedToolChoiceName
    ) {
        this(
                apiKey,
                baseUrl,
                modelName,
                availableTools,
                forcedToolChoiceName,
                null,
                HttpClient.newHttpClient(),
                ProtocolObjectMapperFactory.create()
        );
    }

    OpenAiCompatibleChatModelAdapter(
            String apiKey,
            String baseUrl,
            String modelName,
            List<ToolDefinition> availableTools,
            String forcedToolChoiceName,
            String thinkingModeType
    ) {
        this(
                apiKey,
                baseUrl,
                modelName,
                availableTools,
                forcedToolChoiceName,
                thinkingModeType,
                HttpClient.newHttpClient(),
                ProtocolObjectMapperFactory.create()
        );
    }

    OpenAiCompatibleChatModelAdapter(
            String apiKey,
            String baseUrl,
            String modelName,
            List<ToolDefinition> availableTools,
            String forcedToolChoiceName,
            String thinkingModeType,
            HttpClient httpClient,
            ObjectMapper objectMapper
    ) {
        this.apiKey = requireNonBlank(apiKey, "apiKey must not be blank.");
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.modelName = requireNonBlank(modelName, "modelName must not be blank.");
        this.availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
        this.forcedToolChoiceName = normalizeOptionalText(forcedToolChoiceName);
        this.thinkingModeType = normalizeOptionalText(thinkingModeType);
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
        if (thinkingModeType != null) {
            // 这里只在调用方显式指定时才注入 DeepSeek 的 thinking 开关，
            // 避免把 provider 私有参数无差别扩散到所有 OpenAI 兼容后端。
            // 对上下文摘要这类“只需稳定产出结构化结果”的子模型，
            // 显式关闭 thinking 可以绕开 DeepSeek-V4-Pro 在默认思考模式下拒绝 tool_choice 的兼容性问题。
            requestBody.put("thinking", Map.of("type", thinkingModeType));
        }
        // tool schema 不能只看构造时注入的全局工具，
        // 必须结合当前消息历史里的已激活 Skill 重新计算一次可见工具子集。
        List<ToolDefinition> visibleTools = resolveVisibleTools(messages);
        if (!visibleTools.isEmpty()) {
            requestBody.put("tools", mapTools(visibleTools));
            if (forcedToolChoiceName != null) {
                // 结构化摘要链路要求模型必须调用指定工具，
                // 这里在请求体里显式写入 tool_choice，
                // 避免 DeepSeek 在长上下文下把“应当 tool call”的任务退回成自然语言回答。
                requestBody.put("tool_choice", buildForcedToolChoice(visibleTools));
            }
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

    private List<ToolDefinition> resolveVisibleTools(List<ConversationMessage> messages) {
        if (availableTools.isEmpty()) {
            return List.of();
        }

        List<String> effectiveToolNames = ActivatedSkillSet.fromMessages(messages)
                .resolveEffectiveAllowedTools(availableTools.stream().map(ToolDefinition::name).toList());
        Set<String> effectiveToolNameSet = Set.copyOf(effectiveToolNames);

        // 当前轮次到底把哪些工具暴露给模型，
        // 由消息历史里的已激活 Skill 约束实时计算，
        // 这样 Skill 在会话中途激活后，下一轮 tool schema 就能立即收窄。
        return availableTools.stream()
                .filter(toolDefinition -> effectiveToolNameSet.contains(toolDefinition.name()))
                .toList();
    }

    private List<Map<String, Object>> mapTools(List<ToolDefinition> toolDefinitions) {
        List<Map<String, Object>> tools = new ArrayList<>(toolDefinitions.size());

        for (ToolDefinition toolDefinition : toolDefinitions) {
            // 每个暴露给模型的工具都必须带完整 schema，
            // 否则模型即使看到了工具名，也无法形成稳定可执行的参数结构。
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

    private Map<String, Object> buildForcedToolChoice(List<ToolDefinition> visibleTools) {
        boolean forcedToolVisible = visibleTools.stream()
                .anyMatch(toolDefinition -> toolDefinition.name().equals(forcedToolChoiceName));
        if (!forcedToolVisible) {
            throw new IllegalStateException("Forced tool choice is not visible: " + forcedToolChoiceName);
        }

        Map<String, Object> toolChoice = new LinkedHashMap<>();
        toolChoice.put("type", "function");
        toolChoice.put("function", Map.of("name", forcedToolChoiceName));
        return toolChoice;
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

        JsonNode rootNode = objectMapper.readTree(response.body());
        JsonNode messageNode = rootNode.path("choices").path(0).path("message");
        Optional<TokenUsage> tokenUsage = parseTokenUsage(rootNode);
        JsonNode toolCallsNode = messageNode.path("tool_calls");
        if (toolCallsNode.isArray() && toolCallsNode.size() > 0) {
            return new ToolCallModelResponse(parseToolCalls(toolCallsNode), tokenUsage);
        }

        JsonNode contentNode = messageNode.path("content");
        if (!contentNode.isTextual() || contentNode.asText().isBlank()) {
            throw new IllegalStateException("OpenAI-compatible API response does not contain assistant content.");
        }

        return new FinalModelResponse(contentNode.asText().strip(), tokenUsage);
    }

    private Optional<TokenUsage> parseTokenUsage(JsonNode rootNode) {
        JsonNode usageNode = rootNode.path("usage");
        if (usageNode.isMissingNode() || usageNode.isNull()) {
            return Optional.empty();
        }

        // usage 是真实成本评测的事实来源，字段不完整时必须暴露 provider 响应问题。
        return Optional.of(new TokenUsage(
                readRequiredInt(usageNode, "prompt_tokens"),
                readRequiredInt(usageNode, "completion_tokens"),
                readRequiredInt(usageNode, "total_tokens")
        ));
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
            // OpenAI 兼容模型在工具调用里可能返回数组或对象参数。
            // RepoPilot 当前内部仍然统一使用字符串字典，
            // 因此这里把非字符串值稳定序列化成 JSON 字符串，交给上层受限协议自己解析。
            arguments.put(entry.getKey(), serializeArgumentValue(valueNode));
        });
        return Map.copyOf(arguments);
    }

    private String serializeArgumentValue(JsonNode valueNode) {
        if (valueNode.isTextual()) {
            return valueNode.asText();
        }
        try {
            return objectMapper.writeValueAsString(valueNode);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize OpenAI-compatible tool argument value.", exception);
        }
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

    private int readRequiredInt(JsonNode node, String fieldName) {
        JsonNode fieldNode = node.path(fieldName);
        if (!fieldNode.canConvertToInt()) {
            throw new IllegalStateException("OpenAI-compatible usage field is missing: " + fieldName);
        }
        return fieldNode.asInt();
    }

    private String normalizeBaseUrl(String value) {
        String normalized = requireNonBlank(value, "baseUrl must not be blank.");
        if (normalized.endsWith("/")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
