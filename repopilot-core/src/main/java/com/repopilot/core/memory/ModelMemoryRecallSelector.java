package com.repopilot.core.memory;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 基于模型的记忆候选选择器。
 * 模型只允许返回结构化 id 列表，不能调用工具，也不能直接看到记忆正文。
 */
public final class ModelMemoryRecallSelector implements MemoryRecallSelector {

    private static final String SYSTEM_PROMPT = """
            你是 RepoPilot 的 memory_recall_selector。
            你只能输出一个 JSON object，不允许输出 Markdown、解释文字或代码块。
            你不能调用任何工具；如果你尝试工具调用，本次记忆召回会被判定失败。
            JSON 必须包含 selected_ids 字段，值是字符串数组，数组长度不能超过 3。
            """;

    private final ModelAdapter modelAdapter;
    private final ObjectMapper objectMapper;

    public ModelMemoryRecallSelector(ModelAdapter modelAdapter) {
        this(modelAdapter, ProtocolObjectMapperFactory.create());
    }

    ModelMemoryRecallSelector(ModelAdapter modelAdapter, ObjectMapper objectMapper) {
        this.modelAdapter = Objects.requireNonNull(modelAdapter, "modelAdapter must not be null.");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null.");
    }

    @Override
    public MemoryRecallSelection select(MemoryRecallSelectionRequest request) {
        Objects.requireNonNull(request, "request must not be null.");
        ModelResponse response = modelAdapter.next(buildPrompt(request));
        if (response instanceof ToolCallModelResponse) {
            throw new IllegalStateException("记忆召回 selector 不能调用工具。");
        }
        if (response instanceof FinalModelResponse finalResponse) {
            return parseSelection(finalResponse.message(), request.candidates());
        }
        throw new IllegalStateException("Unsupported memory recall model response type: " + response.getClass().getName());
    }

    private List<ConversationMessage> buildPrompt(MemoryRecallSelectionRequest request) {
        return List.of(
                new ConversationMessage(MessageRole.SYSTEM, SYSTEM_PROMPT),
                new ConversationMessage(MessageRole.USER, renderInput(request))
        );
    }

    private String renderInput(MemoryRecallSelectionRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("prompt: ").append(request.prompt()).append(System.lineSeparator());
        builder.append("run_mode: ").append(request.runMode()).append(System.lineSeparator());
        builder.append("candidates:").append(System.lineSeparator());
        for (MemoryIndexEntry candidate : request.candidates()) {
            // 这里逐行列出 id、type、title、summary 和更新时间。
            // 模型只能基于这些轻量字段做选择，
            // 这样既控制 token 成本，
            // 也避免首版在 selector 阶段提前加载正文。
            builder.append("- id: ").append(candidate.id()).append(System.lineSeparator());
            builder.append("  type: ").append(candidate.type().storageValue()).append(System.lineSeparator());
            builder.append("  title: ").append(candidate.title()).append(System.lineSeparator());
            builder.append("  summary: ").append(candidate.summary()).append(System.lineSeparator());
            builder.append("  updated_at: ").append(candidate.updatedAt()).append(System.lineSeparator());
        }
        return builder.toString().stripTrailing();
    }

    private MemoryRecallSelection parseSelection(String responseText, List<MemoryIndexEntry> candidates) {
        JsonNode rootNode;
        try {
            rootNode = objectMapper.readTree(requireNonBlank(responseText, "memory recall response must not be blank."));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("记忆召回 selector 输出不是合法 JSON: " + exception.getMessage(), exception);
        }
        if (!rootNode.isObject()) {
            throw new IllegalStateException("记忆召回 selector 输出必须是 JSON object。");
        }
        JsonNode selectedIdsNode = rootNode.path("selected_ids");
        if (!selectedIdsNode.isArray()) {
            throw new IllegalStateException("记忆召回 selector 缺少 selected_ids 数组。");
        }

        List<String> selectedIds = new ArrayList<>(selectedIdsNode.size());
        Set<String> knownIds = new LinkedHashSet<>();
        for (MemoryIndexEntry candidate : candidates) {
            knownIds.add(candidate.id());
        }
        for (JsonNode selectedIdNode : selectedIdsNode) {
            if (!selectedIdNode.isTextual() || selectedIdNode.asText().isBlank()) {
                throw new IllegalStateException("记忆召回 selector 的 id 必须是非空字符串。");
            }
            String selectedId = selectedIdNode.asText().strip();
            if (!knownIds.contains(selectedId)) {
                throw new IllegalStateException("记忆召回 selector 返回了未知 id: " + selectedId);
            }
            selectedIds.add(selectedId);
        }
        if (selectedIds.size() > 3) {
            throw new IllegalStateException("记忆召回 selector 最多只能返回 3 条 id。");
        }
        return new MemoryRecallSelection(selectedIds);
    }

    private String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
        return value.strip();
    }
}
