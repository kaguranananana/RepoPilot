package com.repopilot.cli.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 负责从工作区根目录读取本地环境变量文件。
 * 当前只支持最小且明确的 `.env.local` 语法：`KEY=VALUE`。
 */
public final class LocalEnvironmentMapLoader {

    private static final String DOT_ENV_LOCAL_FILE_NAME = ".env.local";
    private static final Pattern ENVIRONMENT_KEY_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private LocalEnvironmentMapLoader() {
    }

    public static Map<String, String> load(Path workspaceRoot, Map<String, String> processEnvironment) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null.");
        Objects.requireNonNull(processEnvironment, "processEnvironment must not be null.");

        Map<String, String> mergedEnvironment = new LinkedHashMap<>();

        // 先加载工作区根目录下的 `.env.local`，
        // 让项目本地私有配置能够进入 CLI 启动链路。
        mergedEnvironment.putAll(readDotEnvLocal(workspaceRoot.resolve(DOT_ENV_LOCAL_FILE_NAME)));

        // 再把真实进程环境覆盖上来，
        // 保证用户显式 export 的值优先级更高，不会被本地文件静默压住。
        mergedEnvironment.putAll(processEnvironment);
        return Map.copyOf(mergedEnvironment);
    }

    private static Map<String, String> readDotEnvLocal(Path dotEnvLocalFile) {
        if (!Files.exists(dotEnvLocalFile)) {
            return Map.of();
        }
        if (!Files.isRegularFile(dotEnvLocalFile)) {
            throw new IllegalArgumentException(".env.local 必须是普通文件。");
        }

        List<String> lines = readAllLines(dotEnvLocalFile);
        Map<String, String> fileEnvironment = new LinkedHashMap<>();

        // 逐行解析 `.env.local`，
        // 一旦发现格式错误，就带着行号直接抛出异常，避免把脏配置悄悄吞掉。
        for (int index = 0; index < lines.size(); index++) {
            String rawLine = lines.get(index);

            // 先统一去掉首尾空白，
            // 这样空行和注释行可以用同一套判断逻辑处理。
            String normalizedLine = rawLine.strip();
            if (normalizedLine.isEmpty() || normalizedLine.startsWith("#")) {
                continue;
            }

            // 再定位第一个 `=`，
            // 这是当前最小语法里 key 和 value 的唯一分隔符。
            int separatorIndex = rawLine.indexOf('=');
            if (separatorIndex <= 0) {
                throw malformedLine(index + 1, rawLine);
            }

            // 左侧按 key 解析并去掉多余空白，
            // 保证 `KEY = value` 这种写法仍然会收敛成标准键名。
            String key = rawLine.substring(0, separatorIndex).strip();
            if (!ENVIRONMENT_KEY_PATTERN.matcher(key).matches()) {
                throw malformedLine(index + 1, rawLine);
            }

            // 右侧整体作为 value，
            // 这里只做首尾空白裁剪，不额外支持引号转义等扩展语法。
            String value = rawLine.substring(separatorIndex + 1).strip();
            if (fileEnvironment.containsKey(key)) {
                throw new IllegalArgumentException(".env.local 第 %d 行重复定义变量: %s".formatted(index + 1, key));
            }
            fileEnvironment.put(key, value);
        }

        return Map.copyOf(fileEnvironment);
    }

    private static List<String> readAllLines(Path dotEnvLocalFile) {
        try {
            return Files.readAllLines(dotEnvLocalFile, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(".env.local 读取失败。", exception);
        }
    }

    private static IllegalArgumentException malformedLine(int lineNumber, String rawLine) {
        return new IllegalArgumentException(".env.local 第 %d 行格式非法: %s".formatted(lineNumber, rawLine));
    }
}
