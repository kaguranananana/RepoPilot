package com.repopilot.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillLoaderTest {

    @TempDir
    Path tempRoot;

    @Test
    void shouldBuildStableSkillIndexFromProjectAndUserRoots() throws Exception {
        Path workspaceRoot = tempRoot.resolve("workspace");
        Path userHome = tempRoot.resolve("home");
        writeSkill(
                workspaceRoot.resolve(".repopilot/skills/refactor-helper"),
                """
                        ---
                        name: refactor-helper
                        description: 帮助拆分大类与长方法
                        allowed-tools:
                          - read_file
                          - grep_files
                        ---
                        """,
                "## Refactor Helper\n按步骤分析职责边界。\n"
        );
        writeSkill(
                userHome.resolve(".repopilot/skills/build-fixer"),
                """
                        ---
                        name: build-fixer
                        description: 聚焦构建失败排查
                        allowed-tools:
                          - run_command
                        ---
                        """,
                "## Build Fixer\n先复现，再缩小范围。\n"
        );

        SkillLoader loader = SkillLoader.forRoots(
                List.of(workspaceRoot.resolve(".repopilot/skills")),
                List.of(userHome.resolve(".repopilot/skills"))
        );

        SkillIndex index = loader.loadIndex();

        assertEquals(
                List.of("build-fixer", "refactor-helper"),
                index.descriptors().stream().map(SkillDescriptor::name).toList()
        );
        assertEquals(
                List.of("user", "project"),
                index.descriptors().stream().map(SkillDescriptor::source).toList()
        );
        assertEquals(
                List.of("run_command"),
                index.requireDescriptor("build-fixer").allowedTools()
        );
        assertEquals(
                List.of("read_file", "grep_files"),
                index.requireDescriptor("refactor-helper").allowedTools()
        );
        assertEquals(
                List.of("build-fixer", "refactor-helper"),
                index.summaries().stream().map(SkillSummary::name).toList()
        );
    }

    @Test
    void shouldLoadSkillBodyAndAttachmentsOnDemand() throws Exception {
        Path workspaceRoot = tempRoot.resolve("workspace");
        Path skillRoot = workspaceRoot.resolve(".repopilot/skills/debug-kit");
        writeSkill(
                skillRoot,
                """
                        ---
                        name: debug-kit
                        description: 结构化排查脚本
                        ---
                        """,
                """
                        ## Debug Kit
                        先读取 scripts/collect.sh，再读取 templates/report.md。
                        """
        );
        Path scriptFile = skillRoot.resolve("scripts/collect.sh");
        Files.createDirectories(scriptFile.getParent());
        Files.writeString(scriptFile, "#!/usr/bin/env bash\necho collect\n");
        Path templateFile = skillRoot.resolve("templates/report.md");
        Files.createDirectories(templateFile.getParent());
        Files.writeString(templateFile, "# 报告模板\n- 现象\n- 根因\n");

        SkillLoader loader = SkillLoader.forRoots(
                List.of(workspaceRoot.resolve(".repopilot/skills")),
                List.of()
        );

        SkillContent content = loader.loadContent("debug-kit");

        assertTrue(content.body().contains("读取 scripts/collect.sh"));
        assertEquals(
                "#!/usr/bin/env bash\necho collect\n",
                loader.loadAttachment("debug-kit", "scripts/collect.sh")
        );
        assertEquals(
                "# 报告模板\n- 现象\n- 根因\n",
                loader.loadAttachment(content, "templates/report.md")
        );
    }

    @Test
    void shouldRejectDuplicateSkillNamesAcrossSources() throws Exception {
        Path workspaceRoot = tempRoot.resolve("workspace");
        Path userHome = tempRoot.resolve("home");
        writeSkill(
                workspaceRoot.resolve(".repopilot/skills/duplicate-project"),
                """
                        ---
                        name: duplicate-skill
                        description: 项目级重复定义
                        ---
                        """,
                "project\n"
        );
        writeSkill(
                userHome.resolve(".repopilot/skills/duplicate-user"),
                """
                        ---
                        name: duplicate-skill
                        description: 用户级重复定义
                        ---
                        """,
                "user\n"
        );

        SkillLoader loader = SkillLoader.forRoots(
                List.of(workspaceRoot.resolve(".repopilot/skills")),
                List.of(userHome.resolve(".repopilot/skills"))
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, loader::loadIndex);

        assertEquals("检测到重复 Skill 名称: duplicate-skill", exception.getMessage());
    }

    private void writeSkill(Path skillRoot, String frontMatter, String body) throws Exception {
        Files.createDirectories(skillRoot);
        Files.writeString(skillRoot.resolve("SKILL.md"), frontMatter + body);
    }
}
