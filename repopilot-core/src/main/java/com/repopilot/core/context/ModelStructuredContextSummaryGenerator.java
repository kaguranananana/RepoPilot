package com.repopilot.core.context;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 通过模型生成结构化上下文摘要。
 * 该组件只接受纯 JSON 文本输出；模型一旦尝试工具调用或输出非法 JSON，就直接暴露错误。
 */
public final class ModelStructuredContextSummaryGenerator implements StructuredContextSummaryGenerator {

    public static final String STRUCTURED_SUMMARY_TOOL_NAME = "submit_structured_context_summary";

    private static final String SYSTEM_PROMPT = """
            你是 RepoPilot 的 structured_context_summary_compressor。
            如果当前可用工具中存在 `submit_structured_context_summary`，你必须调用该工具提交结果，不允许输出自然语言。
            如果当前没有这个工具，你只能输出一个 JSON object，不允许输出 Markdown、解释文字或代码块。
            无论走哪条路径，结果都必须包含这些字段：
            user_goal, current_phase, plan_state, touched_files, important_findings, failed_commands, decisions, next_actions。
            其中 user_goal/current_phase/plan_state 是非空字符串，其余字段是字符串数组。
            """;

    private final ModelAdapter modelAdapter;
    private final ObjectMapper objectMapper;

    public ModelStructuredContextSummaryGenerator(ModelAdapter modelAdapter) {
        this(modelAdapter, ProtocolObjectMapperFactory.create());
    }

    ModelStructuredContextSummaryGenerator(ModelAdapter modelAdapter, ObjectMapper objectMapper) {
        this.modelAdapter = Objects.requireNonNull(modelAdapter, "modelAdapter must not be null.");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null.");
    }

    @Override
    public StructuredContextSummary generate(StructuredContextSummaryRequest request) {
        Objects.requireNonNull(request, "request must not be null.");
        ModelResponse response = modelAdapter.next(buildSummaryPrompt(request));
        if (response instanceof ToolCallModelResponse toolCallResponse) {
            return parseSummaryToolCall(toolCallResponse.toolCalls());
        }
        if (response instanceof FinalModelResponse finalResponse) {
            return parseSummary(finalResponse.message());
        }
        throw new IllegalStateException("Unsupported summary model response type: " + response.getClass().getName());
    }

    private List<ConversationMessage> buildSummaryPrompt(StructuredContextSummaryRequest request) {
        return List.of(
                new ConversationMessage(MessageRole.SYSTEM, SYSTEM_PROMPT),
                new ConversationMessage(MessageRole.USER, renderSummaryInput(request))
        );
    }

    private String renderSummaryInput(StructuredContextSummaryRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("已有工作记忆：").append(System.lineSeparator());
        builder.append(request.workingMemorySnapshot().renderWorkingMemory()).append(System.lineSeparator());
        if (request.workingMemorySnapshot().hasContextSummaryContent()) {
            builder.append(request.workingMemorySnapshot().renderContextSummary()).append(System.lineSeparator());
        }
        builder.append("需要压缩替代的高保真历史：").append(System.lineSeparator());
        for (ConversationMessage message : request.archivedMessages()) {
            builder.append("- role: ").append(message.role()).append(System.lineSeparator());
            if (message.toolCallId() != null) {
                builder.append("  tool_call_id: ").append(message.toolCallId()).append(System.lineSeparator());
            }
            if (!message.toolCalls().isEmpty()) {
                builder.append("  tool_calls: ").append(message.toolCalls()).append(System.lineSeparator());
            }
            builder.append("  content: ").append(message.content()).append(System.lineSeparator());
        }
        return builder.toString().stripTrailing();
    }

    private StructuredContextSummary parseSummary(String responseText) {
        JsonNode rootNode;
        try {
            rootNode = objectMapper.readTree(requireNonBlank(responseText, "summary response must not be blank."));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("结构化摘要模型输出不是合法 JSON: " + exception.getMessage(), exception);
        }
        if (!rootNode.isObject()) {
            throw new IllegalStateException("结构化摘要模型输出必须是 JSON object。");
        }
        return new StructuredContextSummary(
                readRequiredText(rootNode, "user_goal"),
                readRequiredText(rootNode, "current_phase"),
                readRequiredText(rootNode, "plan_state"),
                readRequiredTextArray(rootNode, "touched_files"),
                readRequiredTextArray(rootNode, "important_findings"),
                readRequiredTextArray(rootNode, "failed_commands"),
                readRequiredTextArray(rootNode, "decisions"),
                readRequiredTextArray(rootNode, "next_actions")
        );
    }

    private StructuredContextSummary parseSummaryToolCall(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            throw new IllegalStateException("结构化摘要工具调用结果不能为空。");
        }
        if (toolCalls.size() != 1) {
            throw new IllegalStateException("结构化摘要模型必须且只能调用一次摘要提交工具。");
        }

        ToolCall toolCall = toolCalls.get(0);
        if (!STRUCTURED_SUMMARY_TOOL_NAME.equals(toolCall.toolName())) {
            throw new IllegalStateException("结构化摘要模型只允许调用 " + STRUCTURED_SUMMARY_TOOL_NAME + " 工具。");
        }

        Map<String, String> arguments = toolCall.arguments();
        return new StructuredContextSummary(
                readRequiredArgument(arguments, "user_goal"),
                readRequiredArgument(arguments, "current_phase"),
                readRequiredArgument(arguments, "plan_state"),
                readRequiredArrayArgument(arguments, "touched_files"),
                readRequiredArrayArgument(arguments, "important_findings"),
                readRequiredArrayArgument(arguments, "failed_commands"),
                readRequiredArrayArgument(arguments, "decisions"),
                readRequiredArrayArgument(arguments, "next_actions")
        );
    }

    private String readRequiredText(JsonNode rootNode, String fieldName) {
        JsonNode fieldNode = rootNode.path(fieldName);
        if (!fieldNode.isTextual() || fieldNode.asText().isBlank()) {
            throw new IllegalStateException("结构化摘要字段缺失或为空: " + fieldName);
        }
        return fieldNode.asText().strip();
    }

    private List<String> readRequiredTextArray(JsonNode rootNode, String fieldName) {
        JsonNode fieldNode = rootNode.path(fieldName);
        if (!fieldNode.isArray()) {
            throw new IllegalStateException("结构化摘要字段必须是字符串数组: " + fieldName);
        }
        List<String> values = new ArrayList<>(fieldNode.size());
        for (JsonNode valueNode : fieldNode) {
            if (!valueNode.isTextual() || valueNode.asText().isBlank()) {
                throw new IllegalStateException("结构化摘要数组元素必须是非空字符串: " + fieldName);
            }
            values.add(valueNode.asText().strip());
        }
        return List.copyOf(values);
    }

    private String readRequiredArgument(Map<String, String> arguments, String fieldName) {
        String value = arguments.get(fieldName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("结构化摘要工具参数缺失或为空: " + fieldName);
        }
        return value.strip();
    }

    private List<String> readRequiredArrayArgument(Map<String, String> arguments, String fieldName) {
        String rawValue = readRequiredArgument(arguments, fieldName);
        JsonNode rootNode;
        try {
            // 工具参数在 RepoPilot 内部仍然统一保存为字符串字典，
            // 因此数组型参数会先被模型适配层序列化成 JSON 字符串，
            // 这里再按“必须是字符串数组”的约束反向解析。
            rootNode = objectMapper.readTree(rawValue);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("结构化摘要工具数组参数不是合法 JSON: " + fieldName, exception);
        }
        if (!rootNode.isArray()) {
            throw new IllegalStateException("结构化摘要工具数组参数必须是 JSON 数组: " + fieldName);
        }

        List<String> values = new ArrayList<>(rootNode.size());
        for (JsonNode valueNode : rootNode) {
            // 逐个校验每一项必须是非空字符串，
            // 避免模型把数组里混入 null、数字或空白文本后静默进入 working memory。
            if (!valueNode.isTextual() || valueNode.asText().isBlank()) {
                throw new IllegalStateException("结构化摘要工具数组元素必须是非空字符串: " + fieldName);
            }
            values.add(valueNode.asText().strip());
        }
        return List.copyOf(values);
    }

    public static ToolDefinition summaryToolDefinition() {
        return new ToolDefinition(
                STRUCTURED_SUMMARY_TOOL_NAME,
                "提交结构化上下文摘要。必须一次性填完整 8 个字段；数组字段必须传字符串数组。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "user_goal", Map.of("type", "string"),
                                "current_phase", Map.of("type", "string"),
                                "plan_state", Map.of("type", "string"),
                                "touched_files", Map.of("type", "array", "items", Map.of("type", "string")),
                                "important_findings", Map.of("type", "array", "items", Map.of("type", "string")),
                                "failed_commands", Map.of("type", "array", "items", Map.of("type", "string")),
                                "decisions", Map.of("type", "array", "items", Map.of("type", "string")),
                                "next_actions", Map.of("type", "array", "items", Map.of("type", "string"))
                        ),
                        "required", List.of(
                                "user_goal",
                                "current_phase",
                                "plan_state",
                                "touched_files",
                                "important_findings",
                                "failed_commands",
                                "decisions",
                                "next_actions"
                        ),
                        "additionalProperties", false
                )
        );
    }

    private String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
        return value.strip();
    }
}
