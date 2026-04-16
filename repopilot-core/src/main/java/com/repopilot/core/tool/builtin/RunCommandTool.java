package com.repopilot.core.tool.builtin;

import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolHandler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 在工作区目录内执行单条 shell 命令的内置工具。
 * 当前版本先保证最小语义完整：
 * 1. 校验 command 参数
 * 2. 在工作区目录启动 shell 进程
 * 3. 显式返回 exitCode、stdout、stderr
 */
public final class RunCommandTool implements ToolHandler {

    private final Path workspaceRoot;

    public RunCommandTool(Path workspaceRoot) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null.")
                .toAbsolutePath()
                .normalize();
    }

    @Override
    public ToolExecutionResult execute(Map<String, String> arguments) {
        String commandArgument = requireNonBlankArgument(arguments, "command");
        if (commandArgument == null) {
            return ToolExecutionResult.recoverableError("缺少必填参数: command");
        }

        Process process;
        try {
            // 这里固定用 sh -lc 承载单条命令，
            // 让最小版本先具备常见 shell 语法能力，
            // 同时把执行目录明确钉在工作区根目录。
            process = new ProcessBuilder("sh", "-lc", commandArgument)
                    .directory(workspaceRoot.toFile())
                    .start();
        } catch (IOException exception) {
            return ToolExecutionResult.fatalError("启动命令失败: " + exception.getMessage());
        }

        CompletableFuture<String> stdoutFuture = readStreamAsync(process.getInputStream());
        CompletableFuture<String> stderrFuture = readStreamAsync(process.getErrorStream());

        try {
            int exitCode = process.waitFor();
            String stdout = stdoutFuture.join();
            String stderr = stderrFuture.join();
            String renderedOutput = renderProcessOutput(exitCode, stdout, stderr);
            if (exitCode == 0) {
                return ToolExecutionResult.success(renderedOutput);
            }
            return ToolExecutionResult.recoverableError(renderedOutput);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return ToolExecutionResult.fatalError("命令执行被中断");
        } catch (CompletionException exception) {
            process.destroyForcibly();
            Throwable cause = exception.getCause();
            String message = cause == null ? exception.getMessage() : cause.getMessage();
            return ToolExecutionResult.fatalError("读取命令输出失败: " + message);
        }
    }

    private CompletableFuture<String> readStreamAsync(InputStream inputStream) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream ignored = inputStream) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    private String renderProcessOutput(int exitCode, String stdout, String stderr) {
        return "exitCode: %d\nstdout:\n%s\nstderr:\n%s".formatted(exitCode, stdout, stderr);
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
}
