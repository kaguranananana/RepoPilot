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
 * 写入单个文本文件内容的内置工具。
 * 当前版本只负责最小写盘主链路：
 * 1. 校验 path 与 content 参数
 * 2. 解析目标路径并按需创建父目录
 * 3. 用 UTF-8 直接覆盖写入目标文件
 */
public final class WriteFileTool implements ToolHandler {

    private final Path workspaceRoot;

    public WriteFileTool(Path workspaceRoot) {
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

        // content 允许显式写入空字符串，
        // 因此这里只把“完全缺失”视为参数错误，
        // 不把空内容误判成非法输入。
        String contentArgument = requireArgument(arguments, "content");
        if (contentArgument == null) {
            return ToolExecutionResult.recoverableError("缺少必填参数: content");
        }

        Path targetFile = resolvePath(pathArgument);
        if (Files.exists(targetFile) && !Files.isRegularFile(targetFile)) {
            return ToolExecutionResult.recoverableError("目标不是文件: " + pathArgument);
        }

        try {
            Path parentDirectory = targetFile.getParent();
            if (parentDirectory != null) {
                // 写盘前先确保父目录存在，
                // 这样模型请求创建新文件时不需要额外先跑 mkdir，
                // 同时也把“目录不存在”这种机械性失败从主链路里拿掉。
                Files.createDirectories(parentDirectory);
            }

            Files.writeString(targetFile, contentArgument, StandardCharsets.UTF_8);
            return ToolExecutionResult.success(
                    "已写入文件: %s (%d 行)".formatted(
                            renderPath(targetFile),
                            countLines(contentArgument)
                    )
            );
        } catch (IOException exception) {
            return ToolExecutionResult.fatalError("写入文件失败: " + exception.getMessage());
        }
    }

    private String requireNonBlankArgument(Map<String, String> arguments, String key) {
        String value = requireArgument(arguments, key);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private String requireArgument(Map<String, String> arguments, String key) {
        if (arguments == null) {
            return null;
        }
        return arguments.get(key);
    }

    private Path resolvePath(String pathArgument) {
        Path candidatePath = Path.of(pathArgument);
        if (candidatePath.isAbsolute()) {
            return candidatePath.normalize();
        }
        return workspaceRoot.resolve(candidatePath).normalize();
    }

    private String renderPath(Path targetFile) {
        Path normalizedTargetFile = targetFile.toAbsolutePath().normalize();
        if (normalizedTargetFile.startsWith(workspaceRoot)) {
            return workspaceRoot.relativize(normalizedTargetFile).toString().replace('\\', '/');
        }
        return normalizedTargetFile.toString();
    }

    private int countLines(String content) {
        if (content.isEmpty()) {
            return 0;
        }
        return (int) content.lines().count();
    }
}
