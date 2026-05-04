package com.repopilot.core.memory;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import java.util.List;
import java.util.Objects;

/**
 * 把 recalled memory 聚合成一条临时 `SYSTEM` 消息。
 * 这样下一轮可以整体剥离，避免一条记忆对应一条 system 消息导致历史膨胀。
 */
public final class RecalledMemoryPromptRenderer {

    public ConversationMessage render(List<MemoryRecord> records) {
        Objects.requireNonNull(records, "records must not be null.");
        if (records.isEmpty()) {
            throw new IllegalArgumentException("recalled memory records must not be empty.");
        }

        StringBuilder builder = new StringBuilder("# Recalled Memories");
        for (MemoryRecord record : records) {
            // 每条 recalled memory 都显式带出 id、type、summary、更新时间和正文。
            // 这样模型知道这是一条“历史线索”，
            // 也能看到它的语义标签和新鲜度，
            // 但后续仍必须重新用工具验证当前仓库事实。
            builder.append(System.lineSeparator())
                    .append("- id: ").append(record.id()).append(System.lineSeparator())
                    .append("  type: ").append(record.type().storageValue()).append(System.lineSeparator())
                    .append("  updated_at: ").append(record.updatedAt()).append(System.lineSeparator())
                    .append("  summary: ").append(record.summary()).append(System.lineSeparator())
                    .append("  content:").append(System.lineSeparator())
                    .append("    ")
                    .append(record.body().replace(System.lineSeparator(), System.lineSeparator() + "    "));
        }
        return new ConversationMessage(MessageRole.SYSTEM, builder.toString());
    }
}
