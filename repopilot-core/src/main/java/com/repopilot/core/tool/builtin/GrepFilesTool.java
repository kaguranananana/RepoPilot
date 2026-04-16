package com.repopilot.core.tool.builtin;

import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * 按正则表达式递归搜索文本文件的内置工具。
 * 当前版本聚焦最小主链路：
 * 1. 校验 pattern 参数
 * 2. 从工作区或指定路径递归遍历文件
 * 3. 返回稳定排序后的匹配行
 */
public final class GrepFilesTool implements ToolHandler {

    private final Path workspaceRoot;

    public GrepFilesTool(Path workspaceRoot) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null.")
                .toAbsolutePath()
                .normalize();
    }

    @Override
    public ToolExecutionResult execute(Map<String, String> arguments) {
        String patternArgument = requireNonBlankArgument(arguments, "pattern");
        if (patternArgument == null) {
            return ToolExecutionResult.recoverableError("缺少必填参数: pattern");
        }

        Pattern pattern;
        try {
            pattern = Pattern.compile(patternArgument);
        } catch (PatternSyntaxException exception) {
            return ToolExecutionResult.recoverableError("无效的正则表达式: " + patternArgument);
        }

        Path searchRoot = resolveSearchRoot(arguments);
        if (!Files.exists(searchRoot)) {
            return ToolExecutionResult.recoverableError("搜索路径不存在: " + searchRoot);
        }

        try {
            List<String> matches = collectMatches(searchRoot, pattern);
            if (matches.isEmpty()) {
                return ToolExecutionResult.success("未找到匹配内容: " + patternArgument);
            }
            return ToolExecutionResult.success(String.join("\n", matches));
        } catch (IOException exception) {
            return ToolExecutionResult.fatalError("搜索文件失败: " + exception.getMessage());
        }
    }

    private List<String> collectMatches(Path searchRoot, Pattern pattern) throws IOException {
        List<MatchLine> matches = new ArrayList<>();

        // 先把所有候选文件枚举出来，
        // 再逐个读取并逐行匹配，
        // 最后统一排序，避免不同文件系统遍历顺序导致输出抖动。
        try (Stream<Path> pathStream = Files.isRegularFile(searchRoot) ? Stream.of(searchRoot) : Files.walk(searchRoot)) {
            pathStream
                    .filter(Files::isRegularFile)
                    .forEach(path -> collectMatchesFromSingleFile(path, pattern, matches));
        } catch (GrepSearchIOException exception) {
            throw exception.getCause();
        }

        return matches.stream()
                .sorted(Comparator
                        .comparing(MatchLine::displayPath)
                        .thenComparingInt(MatchLine::lineNumber))
                .map(MatchLine::render)
                .toList();
    }

    private void collectMatchesFromSingleFile(Path filePath, Pattern pattern, List<MatchLine> matches) {
        List<String> lines;
        try {
            lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GrepSearchIOException(exception);
        }

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (pattern.matcher(line).find()) {
                matches.add(new MatchLine(renderPath(filePath), index + 1, line));
            }
        }
    }

    private Path resolveSearchRoot(Map<String, String> arguments) {
        String pathArgument = requireNonBlankArgument(arguments, "path");
        if (pathArgument == null) {
            return workspaceRoot;
        }

        Path candidatePath = Path.of(pathArgument);
        if (candidatePath.isAbsolute()) {
            return candidatePath.normalize();
        }
        return workspaceRoot.resolve(candidatePath).normalize();
    }

    private String renderPath(Path filePath) {
        Path normalizedFilePath = filePath.toAbsolutePath().normalize();
        if (normalizedFilePath.startsWith(workspaceRoot)) {
            return workspaceRoot.relativize(normalizedFilePath).toString().replace('\\', '/');
        }
        return normalizedFilePath.toString();
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

    private record MatchLine(
            String displayPath,
            int lineNumber,
            String lineContent
    ) {

        private String render() {
            return displayPath + ":" + lineNumber + ":" + lineContent;
        }
    }

    private static final class GrepSearchIOException extends RuntimeException {

        private GrepSearchIOException(IOException cause) {
            super(cause);
        }

        @Override
        public IOException getCause() {
            return (IOException) super.getCause();
        }
    }
}
