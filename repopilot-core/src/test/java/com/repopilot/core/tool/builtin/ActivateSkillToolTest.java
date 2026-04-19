package com.repopilot.core.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.skill.SkillLoader;
import com.repopilot.core.tool.ToolExecutionContext;
import com.repopilot.core.tool.ToolExecutionResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActivateSkillToolTest {

    @TempDir
    Path tempRoot;

    @Test
    void shouldActivateSkillAndReturnAdditionalSystemMessage() throws Exception {
        Path workspaceRoot = tempRoot.resolve("workspace");
        Path userHome = tempRoot.resolve("home");
        writeSkill(
                workspaceRoot.resolve(".repopilot/skills/debug"),
                """
                        ---
                        name: debug
                        description: 结构化排查问题
                        ---
                        """,
                "## Debug Skill\n先复现，再缩小范围。\n"
        );
        ActivateSkillTool tool = new ActivateSkillTool(SkillLoader.createDefault(workspaceRoot, userHome));

        ToolExecutionResult result = tool.execute(
                ToolExecutionContext.empty(),
                Map.of("name", "debug")
        );

        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
        assertEquals("Skill debug 已激活。", result.output());
        assertEquals(1, result.appendedMessages().size());
        assertEquals(MessageRole.SYSTEM, result.appendedMessages().get(0).role());
        assertTrue(result.appendedMessages().get(0).content().contains("# Activated Skill"));
    }

    @Test
    void shouldReturnRecoverableErrorWhenSkillDoesNotExist() {
        Path workspaceRoot = tempRoot.resolve("workspace");
        Path userHome = tempRoot.resolve("home");
        ActivateSkillTool tool = new ActivateSkillTool(SkillLoader.createDefault(workspaceRoot, userHome));

        ToolExecutionResult result = tool.execute(
                ToolExecutionContext.empty(),
                Map.of("name", "missing")
        );

        assertEquals(ToolExecutionResult.Status.RECOVERABLE_ERROR, result.status());
        assertEquals("未找到 Skill: missing", result.output());
        assertEquals(List.of(), result.appendedMessages());
    }

    @Test
    void shouldTreatRepeatedSkillActivationAsIdempotentWhenContextAlreadyContainsSkill() throws Exception {
        Path workspaceRoot = tempRoot.resolve("workspace");
        Path userHome = tempRoot.resolve("home");
        writeSkill(
                workspaceRoot.resolve(".repopilot/skills/debug"),
                """
                        ---
                        name: debug
                        description: 结构化排查问题
                        ---
                        """,
                "## Debug Skill\n先复现，再缩小范围。\n"
        );
        ActivateSkillTool tool = new ActivateSkillTool(SkillLoader.createDefault(workspaceRoot, userHome));
        ConversationMessage activatedMessage = new ConversationMessage(
                MessageRole.SYSTEM,
                """
                        # Activated Skill
                        name: debug
                        source: project

                        ## Debug Skill
                        先复现，再缩小范围。
                        """.strip()
        );

        ToolExecutionResult result = tool.execute(
                new ToolExecutionContext(List.of(activatedMessage)),
                Map.of("name", "debug")
        );

        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
        assertEquals("Skill debug 已激活。", result.output());
        assertEquals(List.of(), result.appendedMessages());
    }

    private void writeSkill(Path skillRoot, String frontMatter, String body) throws Exception {
        Files.createDirectories(skillRoot);
        Files.writeString(skillRoot.resolve("SKILL.md"), frontMatter + body);
    }
}
