package com.repopilot.core.edit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 单文件补丁应用服务。
 * 当前补丁格式刻意保持窄语义：
 * 1. 每个 hunk 必须用单独一行 `@@` 开始
 * 2. hunk 内每行必须以空格、`-` 或 `+` 开头
 * 3. 空格行和删除行共同构成待匹配的原始上下文
 * 4. 空格行和新增行共同构成替换后的目标文本
 */
public final class PatchApplyService {

    private final Path workspaceRoot;

    public PatchApplyService(Path workspaceRoot) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null.")
                .toAbsolutePath()
                .normalize();
    }

    public PatchApplyResult preview(PatchApplyRequest request) {
        return applyInternal(request, false);
    }

    public PatchApplyResult apply(PatchApplyRequest request) {
        return applyInternal(request, true);
    }

    private PatchApplyResult applyInternal(PatchApplyRequest request, boolean shouldWrite) {
        Objects.requireNonNull(request, "request must not be null.");
        Path targetFile = resolveWorkspacePath(request.path());
        assertTargetFileExists(targetFile, request.path());

        String beforeContent = readTargetFile(targetFile);
        TextSnapshot beforeSnapshot = TextSnapshot.fromContent(beforeContent);
        PatchPlan patchPlan = parsePatch(request.patch());

        // currentLines 始终表示“已经应用完前面 hunk 后”的最新文件行。
        // 每个 hunk 都在这个最新版本上做精确匹配，避免用旧上下文覆盖前一个 hunk 的修改。
        List<String> currentLines = new ArrayList<>(beforeSnapshot.lines());
        int addedLineCount = 0;
        int removedLineCount = 0;
        for (int index = 0; index < patchPlan.hunks().size(); index++) {
            PatchHunk hunk = patchPlan.hunks().get(index);
            HunkApplyResult hunkResult = applyHunk(currentLines, hunk, index + 1);
            currentLines = hunkResult.linesAfterApply();
            addedLineCount += hunkResult.addedLineCount();
            removedLineCount += hunkResult.removedLineCount();
        }

        String afterContent = beforeSnapshot.render(currentLines);
        if (shouldWrite) {
            writeTargetFile(targetFile, afterContent);
        }
        return buildResult(targetFile, beforeContent, afterContent, addedLineCount, removedLineCount);
    }

    private PatchPlan parsePatch(String patch) {
        List<String> patchLines = patch.lines().toList();
        if (patchLines.isEmpty()) {
            throw invalidPatch("补丁格式非法: 至少需要一个 @@ hunk 标记。");
        }

        List<PatchHunk> hunks = new ArrayList<>();
        List<PatchLine> currentHunkLines = null;
        for (int index = 0; index < patchLines.size(); index++) {
            String line = patchLines.get(index);
            if ("@@".equals(line)) {
                if (currentHunkLines != null) {
                    hunks.add(buildHunk(currentHunkLines, hunks.size() + 1));
                }
                currentHunkLines = new ArrayList<>();
                continue;
            }

            if (currentHunkLines == null) {
                throw invalidPatch("补丁格式非法: 第一条有效行必须是 @@。");
            }
            currentHunkLines.add(parsePatchLine(line, index + 1));
        }

        if (currentHunkLines == null) {
            throw invalidPatch("补丁格式非法: 缺少 @@ hunk 标记。");
        }
        hunks.add(buildHunk(currentHunkLines, hunks.size() + 1));
        return new PatchPlan(hunks);
    }

    private PatchLine parsePatchLine(String line, int lineNumber) {
        if (line.isEmpty()) {
            throw invalidPatch("补丁格式非法: 第 %d 行缺少前缀。".formatted(lineNumber));
        }

        char prefix = line.charAt(0);
        String content = line.substring(1);
        return switch (prefix) {
            case ' ' -> new PatchLine(PatchLineKind.CONTEXT, content);
            case '-' -> new PatchLine(PatchLineKind.REMOVE, content);
            case '+' -> new PatchLine(PatchLineKind.ADD, content);
            default -> throw invalidPatch("补丁格式非法: 第 %d 行必须以空格、- 或 + 开头。".formatted(lineNumber));
        };
    }

    private PatchHunk buildHunk(List<PatchLine> hunkLines, int hunkNumber) {
        if (hunkLines.isEmpty()) {
            throw invalidPatch("补丁格式非法: 第 %d 个 hunk 为空。".formatted(hunkNumber));
        }

        boolean hasChange = hunkLines.stream()
                .anyMatch(line -> line.kind() == PatchLineKind.ADD || line.kind() == PatchLineKind.REMOVE);
        if (!hasChange) {
            throw invalidPatch("补丁格式非法: 第 %d 个 hunk 没有新增或删除行。".formatted(hunkNumber));
        }

        List<String> originalLines = hunkLines.stream()
                .filter(line -> line.kind() != PatchLineKind.ADD)
                .map(PatchLine::content)
                .toList();
        if (originalLines.isEmpty()) {
            throw invalidPatch("补丁格式非法: 第 %d 个 hunk 缺少可匹配的上下文或删除行。".formatted(hunkNumber));
        }

        List<String> replacementLines = hunkLines.stream()
                .filter(line -> line.kind() != PatchLineKind.REMOVE)
                .map(PatchLine::content)
                .toList();
        int addedLineCount = (int) hunkLines.stream().filter(line -> line.kind() == PatchLineKind.ADD).count();
        int removedLineCount = (int) hunkLines.stream().filter(line -> line.kind() == PatchLineKind.REMOVE).count();
        return new PatchHunk(originalLines, replacementLines, addedLineCount, removedLineCount);
    }

    private HunkApplyResult applyHunk(List<String> currentLines, PatchHunk hunk, int hunkNumber) {
        List<Integer> matchIndexes = findExactMatches(currentLines, hunk.originalLines());
        if (matchIndexes.isEmpty()) {
            throw contextMismatch(buildContextMismatchMessage(hunkNumber, hunk.originalLines()));
        }
        if (matchIndexes.size() > 1) {
            throw contextMismatch(
                    buildNonUniqueContextMessage(hunkNumber, matchIndexes.size(), hunk.originalLines())
            );
        }

        int startIndex = matchIndexes.get(0);
        int endIndex = startIndex + hunk.originalLines().size();
        List<String> nextLines = new ArrayList<>();

        // 先复制 hunk 之前的原始内容。
        nextLines.addAll(currentLines.subList(0, startIndex));
        // 再写入 hunk 替换后的内容。
        nextLines.addAll(hunk.replacementLines());
        // 最后接回 hunk 之后的原始内容。
        nextLines.addAll(currentLines.subList(endIndex, currentLines.size()));

        return new HunkApplyResult(nextLines, hunk.addedLineCount(), hunk.removedLineCount());
    }

    private List<Integer> findExactMatches(List<String> currentLines, List<String> originalLines) {
        List<Integer> matchIndexes = new ArrayList<>();
        int lastStartIndex = currentLines.size() - originalLines.size();
        for (int startIndex = 0; startIndex <= lastStartIndex; startIndex++) {
            if (matchesAt(currentLines, originalLines, startIndex)) {
                matchIndexes.add(startIndex);
            }
        }
        return matchIndexes;
    }

    private boolean matchesAt(List<String> currentLines, List<String> originalLines, int startIndex) {
        for (int offset = 0; offset < originalLines.size(); offset++) {
            String currentLine = currentLines.get(startIndex + offset);
            String expectedLine = originalLines.get(offset);
            if (!currentLine.equals(expectedLine)) {
                return false;
            }
        }
        return true;
    }

    private String buildContextMismatchMessage(int hunkNumber, List<String> originalLines) {
        // 这里直接暴露“补丁服务实际尝试匹配的旧块”，
        // 让上层和模型都能看到精确失败对象，而不是只拿到抽象的 mismatch 结论。
        return """
                第 %d 个 hunk 上下文不匹配。
                attemptedOriginalLines:
                %s
                """.formatted(hunkNumber, renderDebugLines(originalLines)).stripTrailing();
    }

    private String buildNonUniqueContextMessage(int hunkNumber, int matchCount, List<String> originalLines) {
        // 多次命中同样需要把真实旧块带出来，
        // 否则上层只能知道“有歧义”，却不知道歧义到底来自哪段文本。
        return """
                第 %d 个 hunk 上下文不唯一，匹配次数: %d。
                attemptedOriginalLines:
                %s
                """.formatted(hunkNumber, matchCount, renderDebugLines(originalLines)).stripTrailing();
    }

    private String renderDebugLines(List<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                builder.append('\n');
            }
            // 行号从 1 开始，方便直接对照 patch hunk 内的相对顺序。
            builder.append(index + 1)
                    .append("| ")
                    .append(lines.get(index));
        }
        return builder.toString();
    }

    private PatchApplyResult buildResult(
            Path targetFile,
            String beforeContent,
            String afterContent,
            int addedLineCount,
            int removedLineCount
    ) {
        String displayPath = renderPath(targetFile);
        PatchApplyResult.ChangeType changeType = beforeContent.equals(afterContent)
                ? PatchApplyResult.ChangeType.NO_CHANGES
                : PatchApplyResult.ChangeType.MODIFY;
        int beforeLineCount = countLines(beforeContent);
        int afterLineCount = countLines(afterContent);
        String summary = """
                PATCH_APPLY
                path: %s
                changeType: %s
                beforeLineCount: %d
                afterLineCount: %d
                addedLineCount: %d
                removedLineCount: %d
                """.formatted(
                displayPath,
                changeType.name(),
                beforeLineCount,
                afterLineCount,
                addedLineCount,
                removedLineCount
        ).stripTrailing();

        return new PatchApplyResult(
                displayPath,
                changeType,
                beforeLineCount,
                afterLineCount,
                addedLineCount,
                removedLineCount,
                summary
        );
    }

    private void assertTargetFileExists(Path targetFile, String pathArgument) {
        if (!targetFile.startsWith(workspaceRoot)) {
            throw new PatchApplyException(
                    ErrorType.WORKSPACE_BOUNDARY_VIOLATION,
                    "补丁目标不能位于工作区外: " + pathArgument
            );
        }
        if (!Files.exists(targetFile)) {
            throw new PatchApplyException(
                    ErrorType.TARGET_FILE_NOT_FOUND,
                    "目标文件不存在: " + pathArgument
            );
        }
        if (!Files.isRegularFile(targetFile)) {
            throw new PatchApplyException(
                    ErrorType.TARGET_FILE_NOT_FOUND,
                    "目标不是普通文件: " + pathArgument
            );
        }
    }

    private String readTargetFile(Path targetFile) {
        try {
            return Files.readString(targetFile, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new PatchApplyException(
                    ErrorType.IO_FAILURE,
                    "读取目标文件失败: " + exception.getMessage(),
                    exception
            );
        }
    }

    private void writeTargetFile(Path targetFile, String content) {
        try {
            Files.writeString(targetFile, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new PatchApplyException(
                    ErrorType.IO_FAILURE,
                    "写入目标文件失败: " + exception.getMessage(),
                    exception
            );
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

    private PatchApplyException invalidPatch(String message) {
        return new PatchApplyException(ErrorType.INVALID_PATCH_FORMAT, message);
    }

    private PatchApplyException contextMismatch(String message) {
        return new PatchApplyException(ErrorType.CONTEXT_MISMATCH, message);
    }

    public enum ErrorType {
        TARGET_FILE_NOT_FOUND,
        INVALID_PATCH_FORMAT,
        CONTEXT_MISMATCH,
        WORKSPACE_BOUNDARY_VIOLATION,
        IO_FAILURE
    }

    public static final class PatchApplyException extends RuntimeException {

        private final ErrorType errorType;

        private PatchApplyException(ErrorType errorType, String message) {
            super(message);
            this.errorType = Objects.requireNonNull(errorType, "errorType must not be null.");
        }

        private PatchApplyException(ErrorType errorType, String message, Throwable cause) {
            super(message, cause);
            this.errorType = Objects.requireNonNull(errorType, "errorType must not be null.");
        }

        public ErrorType errorType() {
            return errorType;
        }
    }

    private record TextSnapshot(
            List<String> lines,
            boolean trailingLineSeparator
    ) {

        private TextSnapshot {
            lines = List.copyOf(lines);
        }

        private static TextSnapshot fromContent(String content) {
            boolean trailingLineSeparator = content.endsWith("\n") || content.endsWith("\r");
            return new TextSnapshot(content.lines().toList(), trailingLineSeparator);
        }

        private String render(List<String> nextLines) {
            if (nextLines.isEmpty()) {
                return "";
            }
            String rendered = String.join("\n", nextLines);
            if (trailingLineSeparator) {
                return rendered + "\n";
            }
            return rendered;
        }
    }

    private record PatchPlan(List<PatchHunk> hunks) {

        private PatchPlan {
            hunks = List.copyOf(hunks);
        }
    }

    private record PatchHunk(
            List<String> originalLines,
            List<String> replacementLines,
            int addedLineCount,
            int removedLineCount
    ) {

        private PatchHunk {
            originalLines = List.copyOf(originalLines);
            replacementLines = List.copyOf(replacementLines);
        }
    }

    private record PatchLine(PatchLineKind kind, String content) {
    }

    private enum PatchLineKind {
        CONTEXT,
        REMOVE,
        ADD
    }

    private record HunkApplyResult(
            List<String> linesAfterApply,
            int addedLineCount,
            int removedLineCount
    ) {

        private HunkApplyResult {
            linesAfterApply = List.copyOf(linesAfterApply);
        }
    }
}
