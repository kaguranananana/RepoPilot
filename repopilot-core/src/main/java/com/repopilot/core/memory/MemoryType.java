package com.repopilot.core.memory;

import java.util.Locale;
import java.util.Objects;

/**
 * 持久记忆类型。
 * 一期只允许四类固定值，避免把首版做成开放分类系统。
 */
public enum MemoryType {
    USER,
    PROJECT,
    FEEDBACK,
    REFERENCE;

    public String storageValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static MemoryType fromStorageValue(String value) {
        Objects.requireNonNull(value, "value must not be null.");
        String normalizedValue = value.strip();
        for (MemoryType type : values()) {
            if (type.storageValue().equals(normalizedValue)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的 memory type: " + value);
    }
}
