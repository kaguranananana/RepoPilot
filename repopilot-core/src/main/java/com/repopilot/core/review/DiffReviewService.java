package com.repopilot.core.review;

import com.repopilot.core.edit.PatchApplyRequest;
import com.repopilot.core.edit.PatchApplyResult;
import com.repopilot.core.edit.PatchApplyService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * 写文件前的最小 diff 审查服务。
 * 当前版本不尝试生成完整 unified diff，
 * 而是先产出稳定、可审计的结构化摘要，供审批链路消费。
 */
public final class DiffReviewService {

    private final Path workspaceRoot;

    public DiffReviewService(Path workspaceRoot) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null.")
                .toAbsolutePath()
                .normalize();
    }

    public boolean requiresReview(String toolName) {
        return "write_file".equals(toolName) || "apply_patch".equals(toolName);
    }

    public DiffReviewSummary review(String toolName, Map<String, String> arguments) {
        Objects.requireNonNull(toolName, "toolName must not be null.");
        Map<String, String> safeArguments = arguments == null ? Map.of() : Map.copyOf(arguments);

        if (!requiresReview(toolName)) {
            throw new IllegalArgumentException("Tool does not require diff review: " + toolName);
        }

        if ("apply_patch".equals(toolName)) {
            return reviewApplyPatch(safeArguments);
        }

        // 写盘审查必须拿到明确的 path 和 content，
        // 缺少任何一个字段都说明调用输入本身不完整，
        // 这时应直接暴露真实错误，而不是猜测写入目标。
        String pathArgument = requireNonBlankArgument(safeArguments, "path");
        String contentArgument = requireArgument(safeArguments, "content");

        Path targetFile = resolveWorkspacePath(pathArgument);
        if (!targetFile.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Diff review target must stay inside workspace: " + pathArgument);
        }

        String beforeContent;
        ChangeType changeType;
        if (Files.exists(targetFile)) {
            beforeContent = readExistingContent(targetFile);
            changeType = beforeContent.equals(contentArgument) ? ChangeType.NO_CHANGES : ChangeType.MODIFY;
        } else {
            beforeContent = "";
            changeType = ChangeType.CREATE;
        }

        String displayPath = renderPath(targetFile);
        int beforeLineCount = countLines(beforeContent);
        int afterLineCount = countLines(contentArgument);

        // 摘要输出固定成多行键值对，
        // 这样后续无论写到 trace、approval 还是日志里，
        // 都能保持稳定且易于解析。
        String summary = """
                DIFF_REVIEW
                path: %s
                changeType: %s
                beforeLineCount: %d
                afterLineCount: %d
                """.formatted(
                displayPath,
                changeType.name(),
                beforeLineCount,
                afterLineCount
        ).stripTrailing();

        return new DiffReviewSummary(
                displayPath,
                changeType,
                beforeLineCount,
                afterLineCount,
                summary
        );
    }

    private DiffReviewSummary reviewApplyPatch(Map<String, String> arguments) {
        String pathArgument = requireNonBlankArgument(arguments, "path");
        String patchArgument = requireNonBlankArgument(arguments, "patch");

        try {
            PatchApplyResult patchResult = new PatchApplyService(workspaceRoot)
                    .preview(new PatchApplyRequest(pathArgument, patchArgument));
            ChangeType changeType = switch (patchResult.changeType()) {
                case MODIFY -> ChangeType.MODIFY;
                case NO_CHANGES -> ChangeType.NO_CHANGES;
            };
            String summary = """
                    DIFF_REVIEW
                    tool: apply_patch
                    path: %s
                    changeType: %s
                    beforeLineCount: %d
                    afterLineCount: %d
                    addedLineCount: %d
                    removedLineCount: %d
                    """.formatted(
                    patchResult.displayPath(),
                    changeType.name(),
                    patchResult.beforeLineCount(),
                    patchResult.afterLineCount(),
                    patchResult.addedLineCount(),
                    patchResult.removedLineCount()
            ).stripTrailing();

            return new DiffReviewSummary(
                    patchResult.displayPath(),
                    changeType,
                    patchResult.beforeLineCount(),
                    patchResult.afterLineCount(),
                    summary
            );
        } catch (PatchApplyService.PatchApplyException exception) {
            throw new DiffReviewFailure(
                    "apply_patch %s: %s".formatted(exception.errorType().name(), exception.getMessage()),
                    exception
            );
        }
    }

    private String readExistingContent(Path targetFile) {
        try {
            return Files.readString(targetFile);
        } catch (Exception exception) {
            throw new IllegalStateException("读取写前内容失败: " + exception.getMessage(), exception);
        }
    }

    private Path resolveWorkspacePath(String pathArgument) {
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

    private String requireNonBlankArgument(Map<String, String> arguments, String key) {
        String value = requireArgument(arguments, key);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return value.strip();
    }

    private String requireArgument(Map<String, String> arguments, String key) {
        String value = arguments.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return value;
    }

    public enum ChangeType {
        CREATE,
        MODIFY,
        NO_CHANGES
    }

    public static final class DiffReviewFailure extends RuntimeException {

        private DiffReviewFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 审查摘要值对象。
     * displayPath 用相对路径优先，避免把工作区绝对路径扩散到上层输出。
     */
    public record DiffReviewSummary(
            String displayPath,
            ChangeType changeType,
            int beforeLineCount,
            int afterLineCount,
            String summary
    ) {

        public DiffReviewSummary {
            displayPath = Objects.requireNonNull(displayPath, "displayPath must not be null.");
            changeType = Objects.requireNonNull(changeType, "changeType must not be null.");
            summary = Objects.requireNonNull(summary, "summary must not be null.");
        }
    }
}
