package com.repopilot.core.skill;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 负责 Skill 的渐进式加载。
 * 1. 默认阶段只扫描项目级和用户级元信息，建立索引。
 * 2. 当调用方明确按名称命中 Skill 时，再读取完整正文。
 * 3. 当正文继续引用脚本、模板或示例时，再按需读取附件。
 */
public final class SkillLoader {

    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String PROJECT_SOURCE = "project";
    private static final String USER_SOURCE = "user";

    private final List<Path> projectSkillRoots;
    private final List<Path> userSkillRoots;

    private SkillLoader(List<Path> projectSkillRoots, List<Path> userSkillRoots) {
        this.projectSkillRoots = normalizeRoots(projectSkillRoots, "projectSkillRoots must not be null.");
        this.userSkillRoots = normalizeRoots(userSkillRoots, "userSkillRoots must not be null.");
    }

    public static SkillLoader createDefault(Path workspaceRoot, Path userHome) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null.");
        Objects.requireNonNull(userHome, "userHome must not be null.");
        return new SkillLoader(
                List.of(workspaceRoot.toAbsolutePath().normalize().resolve(".repopilot/skills")),
                List.of(userHome.toAbsolutePath().normalize().resolve(".repopilot/skills"))
        );
    }

    public static SkillLoader forRoots(List<Path> projectSkillRoots, List<Path> userSkillRoots) {
        return new SkillLoader(projectSkillRoots, userSkillRoots);
    }

    public SkillIndex loadIndex() {
        List<SkillDescriptor> descriptors = new ArrayList<>();

        // 先扫描项目级目录，
        // 保证仓库本地声明的 Skill 会进入同一份索引。
        descriptors.addAll(scanRoots(projectSkillRoots, PROJECT_SOURCE));

        // 再扫描用户级目录，
        // 让个人级 Skill 也能进入统一索引，但不引入静默覆盖逻辑。
        descriptors.addAll(scanRoots(userSkillRoots, USER_SOURCE));

        return new SkillIndex(descriptors);
    }

    public SkillContent loadContent(String skillName) {
        return loadContent(loadIndex().requireDescriptor(skillName));
    }

    public SkillContent loadContent(SkillDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null.");
        return new SkillContent(descriptor, readFile(descriptor.skillFile(), "Skill 正文读取失败"));
    }

    public String loadAttachment(String skillName, String relativePath) {
        return loadAttachment(loadContent(skillName), relativePath);
    }

    public String loadAttachment(SkillContent skillContent, String relativePath) {
        Objects.requireNonNull(skillContent, "skillContent must not be null.");
        Path attachmentPath = resolveAttachmentPath(skillContent.rootDirectory(), relativePath);
        return readFile(attachmentPath, "Skill 附件读取失败");
    }

    private List<SkillDescriptor> scanRoots(List<Path> roots, String source) {
        List<SkillDescriptor> descriptors = new ArrayList<>();
        for (Path root : roots) {
            descriptors.addAll(scanSingleRoot(root, source));
        }
        return descriptors;
    }

    private List<SkillDescriptor> scanSingleRoot(Path root, String source) {
        if (!Files.exists(root)) {
            return List.of();
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Skill 根目录必须是目录: " + root);
        }

        try (Stream<Path> pathStream = Files.find(
                root,
                Integer.MAX_VALUE,
                (path, attributes) -> attributes.isRegularFile() && path.getFileName().toString().equals(SKILL_FILE_NAME)
        )) {
            return pathStream
                    .sorted()
                    .map(skillFile -> parseDescriptor(root, skillFile, source))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("Skill 索引扫描失败: " + root, exception);
        }
    }

    private SkillDescriptor parseDescriptor(Path root, Path skillFile, String source) {
        Map<String, Object> metadata = readFrontMatter(skillFile);
        Object nameValue = metadata.get("name");
        Object descriptionValue = metadata.get("description");
        if (descriptionValue == null) {
            descriptionValue = metadata.get("summary");
        }

        return new SkillDescriptor(
                requireMetadataValue(nameValue, "Skill 缺少 name 元信息: " + skillFile),
                requireMetadataValue(descriptionValue, "Skill 缺少 description 元信息: " + skillFile),
                source,
                skillFile.getParent(),
                skillFile,
                castAllowedTools(metadata.get("allowed-tools"))
        );
    }

    private Map<String, Object> readFrontMatter(Path skillFile) {
        try (BufferedReader reader = Files.newBufferedReader(skillFile, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            if (firstLine == null || !firstLine.strip().equals("---")) {
                throw new IllegalArgumentException("Skill 文件缺少 front matter: " + skillFile);
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            String currentListKey = null;

            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    throw new IllegalArgumentException("Skill front matter 未正确闭合: " + skillFile);
                }
                String strippedLine = line.strip();

                // 读到结束分隔符后立即停止，
                // 保证索引阶段不会继续把正文整段吃进来。
                if (strippedLine.equals("---")) {
                    break;
                }
                if (strippedLine.isEmpty()) {
                    continue;
                }

                // `allowed-tools` 采用最小 YAML 列表语法，
                // 只有在上一行显式声明列表键后，`- item` 才被接受。
                if (strippedLine.startsWith("- ")) {
                    if (currentListKey == null) {
                        throw new IllegalArgumentException("Skill front matter 列表项缺少键名: " + skillFile);
                    }
                    @SuppressWarnings("unchecked")
                    List<String> values = (List<String>) metadata.get(currentListKey);
                    values.add(requireNonBlank(
                            strippedLine.substring(2),
                            "Skill front matter 列表项不能为空: " + skillFile
                    ));
                    continue;
                }

                int separatorIndex = strippedLine.indexOf(':');
                if (separatorIndex <= 0) {
                    throw new IllegalArgumentException("Skill front matter 行格式非法: " + skillFile + " -> " + line);
                }

                String key = strippedLine.substring(0, separatorIndex).strip();
                String value = strippedLine.substring(separatorIndex + 1).strip();

                // 每读到一个新键，都先清掉前一个列表上下文，
                // 避免后续 `- item` 被错误追加到旧键上。
                currentListKey = null;

                if (metadata.containsKey(key)) {
                    throw new IllegalArgumentException("Skill front matter 重复键: " + key + " (" + skillFile + ")");
                }

                if (value.isEmpty()) {
                    if (key.equals("allowed-tools")) {
                        List<String> values = new ArrayList<>();
                        metadata.put(key, values);
                        currentListKey = key;
                        continue;
                    }
                    throw new IllegalArgumentException("Skill front matter 键缺少值: " + key + " (" + skillFile + ")");
                }

                metadata.put(key, value);
            }

            return Map.copyOf(metadata);
        } catch (IOException exception) {
            throw new UncheckedIOException("Skill 元信息读取失败: " + skillFile, exception);
        }
    }

    private List<String> castAllowedTools(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof String stringValue) {
            return List.of(requireNonBlank(stringValue, "Skill allowed-tools 不能为空。"));
        }
        if (value instanceof List<?> rawList) {
            List<String> allowedTools = new ArrayList<>(rawList.size());
            for (Object item : rawList) {
                if (!(item instanceof String stringItem)) {
                    throw new IllegalArgumentException("Skill allowed-tools 必须全部是字符串。");
                }
                allowedTools.add(requireNonBlank(stringItem, "Skill allowed-tools 不能为空。"));
            }
            return List.copyOf(allowedTools);
        }
        throw new IllegalArgumentException("Skill allowed-tools 元信息类型非法。");
    }

    private Path resolveAttachmentPath(Path skillRoot, String relativePath) {
        String safeRelativePath = requireNonBlank(relativePath, "Skill attachment path must not be blank.");
        Path candidatePath = Path.of(safeRelativePath);

        // 附件路径必须是 Skill 根目录内的相对路径，
        // 这样模板和脚本按需加载时不会越权读到目录外的文件。
        if (candidatePath.isAbsolute()) {
            throw new IllegalArgumentException("Skill 附件路径必须是相对路径: " + relativePath);
        }

        Path resolvedPath = skillRoot.resolve(candidatePath).normalize();
        if (!resolvedPath.startsWith(skillRoot)) {
            throw new IllegalArgumentException("Skill 附件路径越界: " + relativePath);
        }
        if (!Files.exists(resolvedPath)) {
            throw new IllegalArgumentException("Skill 附件不存在: " + relativePath);
        }
        if (!Files.isRegularFile(resolvedPath)) {
            throw new IllegalArgumentException("Skill 附件必须是普通文件: " + relativePath);
        }
        return resolvedPath;
    }

    private String readFile(Path path, String prefix) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(prefix + ": " + path, exception);
        }
    }

    private static List<Path> normalizeRoots(List<Path> roots, String message) {
        Objects.requireNonNull(roots, message);

        List<Path> normalized = new ArrayList<>(roots.size());
        for (Path root : roots) {
            normalized.add(Objects.requireNonNull(root, "Skill root must not be null.").toAbsolutePath().normalize());
        }
        return List.copyOf(normalized);
    }

    private static String requireMetadataValue(Object value, String message) {
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return stringValue.strip();
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
