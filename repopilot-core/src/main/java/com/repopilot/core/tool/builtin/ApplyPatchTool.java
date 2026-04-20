package com.repopilot.core.tool.builtin;

import com.repopilot.core.edit.PatchApplyRequest;
import com.repopilot.core.edit.PatchApplyResult;
import com.repopilot.core.edit.PatchApplyService;
import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolHandler;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * 对单个工作区文件应用最小文本补丁的内置工具。
 */
public final class ApplyPatchTool implements ToolHandler {

    private final PatchApplyService patchApplyService;

    public ApplyPatchTool(Path workspaceRoot) {
        this.patchApplyService = new PatchApplyService(
                Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null.")
        );
    }

    @Override
    public ToolExecutionResult execute(Map<String, String> arguments) {
        String pathArgument = requireNonBlankArgument(arguments, "path");
        if (pathArgument == null) {
            return ToolExecutionResult.recoverableError("缺少必填参数: path");
        }

        String patchArgument = requireNonBlankPatchArgument(arguments, "patch");
        if (patchArgument == null) {
            return ToolExecutionResult.recoverableError("缺少必填参数: patch");
        }

        try {
            PatchApplyResult result = patchApplyService.apply(new PatchApplyRequest(pathArgument, patchArgument));
            return ToolExecutionResult.success(result.summary());
        } catch (PatchApplyService.PatchApplyException exception) {
            if (exception.errorType() == PatchApplyService.ErrorType.IO_FAILURE) {
                return ToolExecutionResult.fatalError(renderPatchError(exception));
            }
            return ToolExecutionResult.recoverableError(renderPatchError(exception));
        }
    }

    private String renderPatchError(PatchApplyService.PatchApplyException exception) {
        return "补丁应用失败: %s: %s".formatted(exception.errorType().name(), exception.getMessage());
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

    private String requireNonBlankPatchArgument(Map<String, String> arguments, String key) {
        if (arguments == null) {
            return null;
        }

        String value = arguments.get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        // patch 参数中的首尾空白可能属于待新增或待删除文本，
        // 工具层不能在应用前改写它。
        return value;
    }
}
