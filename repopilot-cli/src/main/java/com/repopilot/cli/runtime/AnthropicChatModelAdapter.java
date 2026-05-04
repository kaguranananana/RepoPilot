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
 * Anthropic Messages 非流式聊天模型适配器。
 * 当前版本只覆盖最小真实主链路：
 * 1. 把 RepoPilot 消息映射成 Anthropic Messages 结构
 * 2. 调用 Anthropic `/v1/messages`
 * 3. 解析 assistant 文本回答或 `tool_use`
 * 4. 在下一轮把 `assistant(tool_use) -> user(tool_result)` 正确回注给模型
 */
public final class AnthropicChatModelAdapter implements ModelAdapter {

    static final String DEFAULT_ANTHROPIC_VERSION = "2023-06-01";
    static final int DEFAULT_MAX_TOKENS = 4096;

    private final String apiKey;
    private final String baseUrl;
    private final String modelName;
    private final List<ToolDefinition> availableTools;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AnthropicChatModelAdapter(
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

    AnthropicChatModelAdapter(
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
                    // Anthropic 主链路固定走 `/v1/messages`，
                    // 这里显式拼接该路径，避免把它误当成 OpenAI 兼容入口。
                    .uri(URI.create(baseUrl + "/v1/messages"))
                    // Anthropic 使用 `x-api-key` 承载密钥，
                    // 不能复用 OpenAI 风格的 Bearer Authorization。
                    .header("x-api-key", apiKey)
                    // 非流式 Messages API 仍要求显式版本头，
                    // 这里固定使用当前最小接入版本，避免由网关默认值漂移。
                    .header("anthropic-version", DEFAULT_ANTHROPIC_VERSION)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(messages)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseResponse(response);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to call Anthropic API: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Anthropic API call was interrupted.", exception);
        }
    }

    private String buildRequestBody(List<ConversationMessage> messages) throws IOException {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", modelName);
        // Anthropic Messages API 要求显式给出 `max_tokens`，
        // 当前先固定一个稳定默认值，避免为这次接入引入额外配置面。
        requestBody.put("max_tokens", DEFAULT_MAX_TOKENS);

        String systemPrompt = buildSystemPrompt(messages);
        if (!systemPrompt.isBlank()) {
            requestBody.put("system", systemPrompt);
        }

        requestBody.put("messages", mapMessages(messages));

        // tool schema 不能只看构造时注入的全局工具，
        // 必须结合当前消息历史里的已激活 Skill 重新计算一次可见工具子集。
        List<ToolDefinition> visibleTools = resolveVisibleTools(messages);
        if (!visibleTools.isEmpty()) {
            requestBody.put("tools", mapTools(visibleTools));
        }
        return objectMapper.writeValueAsString(requestBody);
    }

    private String buildSystemPrompt(List<ConversationMessage> messages) {
        StringBuilder builder = new StringBuilder();

        for (ConversationMessage message : messages) {
            // Anthropic 的 system prompt 是顶层字段，
            // 所以这里把所有系统态上下文按历史顺序折叠成一段稳定字符串。
            if (!isSystemLikeMessage(message)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(requireNonBlank(message.content(), "message content must not be blank."));
        }

        return builder.toString();
    }

    private List<Map<String, Object>> mapMessages(List<ConversationMessage> messages) {
        List<Map<String, Object>> apiMessages = new ArrayList<>();

        // Anthropic 不支持把 TOOL 作为独立 role 发送，
        // 因此这里按顺序扫描内部历史，并把连续 TOOL 消息折叠成一个 user(tool_result) 消息。
        for (int index = 0; index < messages.size(); ) {
            ConversationMessage message = messages.get(index);
            Objects.requireNonNull(message, "message must not be null.");

            if (isSystemLikeMessage(message)) {
                index += 1;
                continue;
            }

            if (message.role() == MessageRole.TOOL) {
                List<ConversationMessage> toolMessages = new ArrayList<>();
                while (index < messages.size() && messages.get(index).role() == MessageRole.TOOL) {
                    toolMessages.add(messages.get(index));
                    index += 1;
                }
                apiMessages.add(mapToolResultMessage(toolMessages));
                continue;
            }

            apiMessages.add(mapStandardMessage(message));
            index += 1;
        }

        return List.copyOf(apiMessages);
    }

    private Map<String, Object> mapStandardMessage(ConversationMessage message) {
        Map<String, Object> apiMessage = new LinkedHashMap<>();

        switch (message.role()) {
            case USER -> {
                apiMessage.put("role", "user");
                apiMessage.put("content", List.of(textBlock(message.content())));
            }
            case ASSISTANT -> {
                apiMessage.put("role", "assistant");
                apiMessage.put("content", mapAssistantContent(message));
            }
            default -> throw new IllegalStateException("Unsupported Anthropic message role: " + message.role());
        }

        return apiMessage;
    }

    private List<Map<String, Object>> mapAssistantContent(ConversationMessage message) {
        List<Map<String, Object>> contentBlocks = new ArrayList<>();

        // assistant 的普通文本要先保留下来，
        // 这样模型此前给用户的可见说明不会在下一轮历史中消失。
        if (!message.content().isBlank()) {
            contentBlocks.add(textBlock(message.content()));
        }

        for (ToolCall toolCall : message.toolCalls()) {
            Map<String, Object> toolUseBlock = new LinkedHashMap<>();
            // Anthropic 使用 content block 表达工具请求，
            // 因此这里逐个把内部 ToolCall 映射成 `tool_use`。
            toolUseBlock.put("type", "tool_use");
            toolUseBlock.put("id", toolCall.id());
            toolUseBlock.put("name", toolCall.toolName());
            toolUseBlock.put("input", new LinkedHashMap<>(toolCall.arguments()));
            contentBlocks.add(toolUseBlock);
        }

        if (contentBlocks.isEmpty()) {
            throw new IllegalStateException("Anthropic assistant message must contain text or tool_use content.");
        }

        return List.copyOf(contentBlocks);
    }

    private Map<String, Object> mapToolResultMessage(List<ConversationMessage> toolMessages) {
        List<Map<String, Object>> contentBlocks = new ArrayList<>(toolMessages.size());

        for (ConversationMessage toolMessage : toolMessages) {
            Map<String, Object> toolResultBlock = new LinkedHashMap<>();
            // Anthropic 要求工具结果作为 `user` 消息里的 `tool_result` block 回注，
            // 这里显式保留 tool_call_id 与文本结果的对应关系。
            toolResultBlock.put("type", "tool_result");
            toolResultBlock.put("tool_use_id", requireNonBlank(
                    toolMessage.toolCallId(),
                    "toolCallId must not be blank."
            ));
            toolResultBlock.put("content", requireNonBlank(
                    toolMessage.content(),
                    "message content must not be blank."
            ));
            contentBlocks.add(toolResultBlock);
        }

        Map<String, Object> apiMessage = new LinkedHashMap<>();
        apiMessage.put("role", "user");
        apiMessage.put("content", List.copyOf(contentBlocks));
        return apiMessage;
    }

    private Map<String, Object> textBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", requireNonBlank(text, "message content must not be blank."));
        return block;
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
            tool.put("name", toolDefinition.name());
            tool.put("description", toolDefinition.description());
            tool.put("input_schema", toolDefinition.parametersSchema());
            tools.add(tool);
        }

        return List.copyOf(tools);
    }

    private ModelResponse parseResponse(HttpResponse<String> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Anthropic API request failed with status %d: %s".formatted(
                            response.statusCode(),
                            response.body()
                    )
            );
        }

        JsonNode rootNode = objectMapper.readTree(response.body());
        JsonNode contentNode = rootNode.path("content");
        Optional<TokenUsage> tokenUsage = parseTokenUsage(rootNode);
        List<ToolCall> toolCalls = parseToolUses(contentNode);
        if (!toolCalls.isEmpty()) {
            return new ToolCallModelResponse(toolCalls, tokenUsage);
        }

        String assistantText = parseAssistantText(contentNode);
        return new FinalModelResponse(assistantText, tokenUsage);
    }

    private Optional<TokenUsage> parseTokenUsage(JsonNode rootNode) {
        JsonNode usageNode = rootNode.path("usage");
        if (usageNode.isMissingNode() || usageNode.isNull()) {
            return Optional.empty();
        }

        // 先读取 prompt token。
        // 某些网关会直接返回 `prompt_tokens`，
        // 这个值已经把缓存命中的输入一起算进来了，最适合作为真实输入成本。
        // 如果网关没给 `prompt_tokens`，就退回到 Anthropic 原生 usage 字段：
        // `input_tokens + cache_creation_input_tokens + cache_read_input_tokens`。
        int promptTokens = readPromptTokens(usageNode);
        // completion token 同样优先读显式总量字段，
        // 没有时再退回到 Anthropic 原生的 `output_tokens`。
        int completionTokens = readCompletionTokens(usageNode);
        // total token 如果 provider 已经给出，就直接采用 provider 事实；
        // 否则按 prompt + completion 组装，保持内部结构完整。
        int totalTokens = readTotalTokens(usageNode, promptTokens, completionTokens);
        return Optional.of(new TokenUsage(promptTokens, completionTokens, totalTokens));
    }

    private int readPromptTokens(JsonNode usageNode) {
        JsonNode promptTokensNode = usageNode.path("prompt_tokens");
        if (promptTokensNode.canConvertToInt()) {
            return promptTokensNode.asInt();
        }

        // 这里逐项把 Anthropic 原生 usage 字段相加，
        // 避免缓存命中时 `input_tokens=0` 被误判成“输入成本为 0”。
        int inputTokens = readRequiredInt(usageNode, "input_tokens");
        int cacheCreationInputTokens = readOptionalInt(usageNode, "cache_creation_input_tokens");
        int cacheReadInputTokens = readOptionalInt(usageNode, "cache_read_input_tokens");
        return inputTokens + cacheCreationInputTokens + cacheReadInputTokens;
    }

    private int readCompletionTokens(JsonNode usageNode) {
        JsonNode completionTokensNode = usageNode.path("completion_tokens");
        if (completionTokensNode.canConvertToInt()) {
            return completionTokensNode.asInt();
        }
        return readRequiredInt(usageNode, "output_tokens");
    }

    private int readTotalTokens(JsonNode usageNode, int promptTokens, int completionTokens) {
        JsonNode totalTokensNode = usageNode.path("total_tokens");
        if (totalTokensNode.canConvertToInt()) {
            return totalTokensNode.asInt();
        }
        return promptTokens + completionTokens;
    }

    private List<ToolCall> parseToolUses(JsonNode contentNode) throws IOException {
        if (!contentNode.isArray()) {
            throw new IllegalStateException("Anthropic API response content must be an array.");
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode contentBlockNode : contentNode) {
            if (!"tool_use".equals(contentBlockNode.path("type").asText())) {
                continue;
            }
            String id = readRequiredText(contentBlockNode, "id");
            String toolName = readRequiredText(contentBlockNode, "name");
            JsonNode inputNode = contentBlockNode.path("input");
            toolCalls.add(new ToolCall(id, toolName, parseArguments(inputNode)));
        }
        return List.copyOf(toolCalls);
    }

    private String parseAssistantText(JsonNode contentNode) {
        if (!contentNode.isArray()) {
            throw new IllegalStateException("Anthropic API response content must be an array.");
        }

        List<String> textBlocks = new ArrayList<>();
        for (JsonNode contentBlockNode : contentNode) {
            if (!"text".equals(contentBlockNode.path("type").asText())) {
                continue;
            }
            String text = readRequiredText(contentBlockNode, "text");
            textBlocks.add(text);
        }

        if (textBlocks.isEmpty()) {
            throw new IllegalStateException("Anthropic API response does not contain assistant text.");
        }

        return String.join("\n", textBlocks);
    }

    private Map<String, String> parseArguments(JsonNode argumentsNode) {
        if (!argumentsNode.isObject()) {
            throw new IllegalStateException("Tool call arguments must be a JSON object.");
        }

        Map<String, String> arguments = new LinkedHashMap<>();
        argumentsNode.fields().forEachRemaining(entry -> {
            JsonNode valueNode = entry.getValue();
            // RepoPilot 运行时当前仍以 `Map<String, String>` 贯穿工具参数，
            // 所以这里对字符串直接透传，
            // 对数组 / 对象 / 布尔值等结构化值统一序列化成 JSON 字符串。
            // 这样已有工具保持不变，同时允许结构化摘要这类受限协议承载数组字段。
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
            throw new IllegalStateException("Failed to serialize Anthropic tool argument value.", exception);
        }
    }

    private String readRequiredText(JsonNode node, String fieldName) {
        JsonNode fieldNode = node.path(fieldName);
        if (!fieldNode.isTextual() || fieldNode.asText().isBlank()) {
            throw new IllegalStateException("Anthropic response field is missing: " + fieldName);
        }
        return fieldNode.asText().strip();
    }

    private int readRequiredInt(JsonNode node, String fieldName) {
        JsonNode fieldNode = node.path(fieldName);
        if (!fieldNode.canConvertToInt()) {
            throw new IllegalStateException("Anthropic usage field is missing: " + fieldName);
        }
        return fieldNode.asInt();
    }

    private int readOptionalInt(JsonNode node, String fieldName) {
        JsonNode fieldNode = node.path(fieldName);
        if (!fieldNode.canConvertToInt()) {
            return 0;
        }
        return fieldNode.asInt();
    }

    private boolean isSystemLikeMessage(ConversationMessage message) {
        return message.role() == MessageRole.SYSTEM
                || message.role() == MessageRole.WORKING_MEMORY
                || message.role() == MessageRole.CONTEXT_SUMMARY;
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
