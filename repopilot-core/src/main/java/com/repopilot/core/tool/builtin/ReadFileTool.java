package com.repopilot.core.tool.builtin;

import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * 读取单个文本文件内容的内置工具。
 * 当前版本只做最小主链路：
 * 1. 校验 path 参数
 * 2. 按工作区根目录解析相对路径
 * 3. 读取 UTF-8 文本并原样返回
 */
public final class ReadFileTool implements ToolHandler {

    private final Path workspaceRoot;

    public ReadFileTool(Path workspaceRoot) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null.")
                .toAbsolutePath()
                .normalize();
    }

    @Override
    public ToolExecutionResult execute(Map<String, String> arguments) {
        String pathArgument = requireNonBlankArgument(arguments, "path");
        if (pathArgument == null) {
            return ToolExecutionResult.recoverableError("缺少必填参数: path");
        }

        Path targetFile = resolvePath(pathArgument);
        if (!Files.exists(targetFile)) {
            return ToolExecutionResult.recoverableError("文件不存在: " + pathArgument);
        }
        if (!Files.isRegularFile(targetFile)) {
            return ToolExecutionResult.recoverableError("目标不是文件: " + pathArgument);
        }

        try {
            // 这里直接按 UTF-8 读取整个文件，
            // 先保证“模型请求读文件 -> 运行时返回真实内容”这条主链路正确成立。
            return ToolExecutionResult.success(Files.readString(targetFile, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            return ToolExecutionResult.fatalError("读取文件失败: " + exception.getMessage());
        }
    }

    private String requireNonBlankArgument(Map<String, String> arguments, String key) {
        if (arguments == null) {
            return null;
        }

        String value = arguments.get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private Path resolvePath(String pathArgument) {
        Path candidatePath = Path.of(pathArgument);
        if (candidatePath.isAbsolute()) {
            return candidatePath.normalize();
        }
        return workspaceRoot.resolve(candidatePath).normalize();
    }
}
