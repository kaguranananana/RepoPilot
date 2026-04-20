package com.repopilot.core.edit;

import java.util.Objects;

/**
 * 单文件补丁应用请求。
 * path 负责绑定目标文件，patch 负责描述最小文本修改。
 */
public record PatchApplyRequest(
        String path,
        String patch
) {

    public PatchApplyRequest {
        path = requireNonBlankAndStrip(path, "path must not be blank.");
        patch = requireNonBlankPatch(patch, "patch must not be blank.");
    }

    private static String requireNonBlankAndStrip(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    private static String requireNonBlankPatch(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        // patch 文本里的空白是补丁语义本身，
        // 因此这里只做非空校验，不对原始文本执行 strip。
        return value;
    }
}
