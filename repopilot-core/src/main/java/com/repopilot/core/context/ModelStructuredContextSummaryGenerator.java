package com.repopilot.core.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCallModelResponse;
import com.repopilot.protocol.json.ProtocolObjectMapperFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 通过模型生成结构化上下文摘要。
 * 该组件只接受纯 JSON 文本输出；模型一旦尝试工具调用或输出非法 JSON，就直接暴露错误。
 */
public final class ModelStructuredContextSummaryGenerator implements StructuredContextSummaryGenerator {

    private static final String SYSTEM_PROMPT = """
            你是 RepoPilot 的 structured_context_summary_compressor。
            你只能输出一个 JSON object，不允许输出 Markdown、解释文字或代码块。
            你不能调用任何工具；如果你尝试工具调用，本次压缩会被判定失败。
            JSON 必须包含这些字段：
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
        if (response instanceof ToolCallModelResponse) {
            throw new IllegalStateException("结构化摘要模型不能调用工具。");
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

    private String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
        return value.strip();
    }
}
