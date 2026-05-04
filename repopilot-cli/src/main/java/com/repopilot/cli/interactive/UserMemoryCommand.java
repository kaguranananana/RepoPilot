package com.repopilot.cli.interactive;

import java.util.Objects;
import java.util.Optional;

/**
 * 显式记忆命令。
 * 一期只支持固定的四种管理动作，不做自然语言记忆意图推断。
 */
public record UserMemoryCommand(Type type, String memoryId) {

    public UserMemoryCommand {
        type = Objects.requireNonNull(type, "type must not be null.");
        memoryId = normalizeOptionalText(memoryId);
        if (type.requiresId() && memoryId == null) {
            throw new IllegalArgumentException("Memory 命令缺少 id: " + type);
        }
    }

    public Optional<String> id() {
        return Optional.ofNullable(memoryId);
    }

    public enum Type {
        REMEMBER(false),
        LIST(false),
        SHOW(true),
        FORGET(true);

        private final boolean requiresId;

        Type(boolean requiresId) {
            this.requiresId = requiresId;
        }

        public boolean requiresId() {
            return requiresId;
        }
    }

    private static String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
