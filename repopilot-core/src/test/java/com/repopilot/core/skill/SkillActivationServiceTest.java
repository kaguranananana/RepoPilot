package com.repopilot.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillActivationServiceTest {

    @TempDir
    Path tempRoot;

    @Test
    void shouldActivateExistingSkillIntoSystemMessage() throws Exception {
        SkillActivationService activationService = createActivationService();
        writeSkill(
                tempRoot.resolve("workspace/.repopilot/skills/debug"),
                """
                        ---
                        name: debug
                        description: 结构化排查测试失败
                        ---
                        """,
                "## Debug Skill\n先复现，再缩小范围。\n"
        );

        SkillActivationResult result = activationService.activate(ActivatedSkillSet.empty(), "debug");

        assertTrue(result.activatedNow());
        assertEquals("debug", result.skillName());
        assertEquals("Skill debug 已激活。", result.output());
        assertEquals(1, result.appendedMessages().size());
        ConversationMessage activatedMessage = result.appendedMessages().get(0);
        assertEquals(MessageRole.SYSTEM, activatedMessage.role());
        assertTrue(activatedMessage.content().contains("# Activated Skill"));
        assertTrue(activatedMessage.content().contains("name: debug"));
        assertTrue(activatedMessage.content().contains("source: project"));
        assertTrue(activatedMessage.content().contains("## Debug Skill"));
    }

    @Test
    void shouldRejectMissingSkill() {
        SkillActivationService activationService = createActivationService();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> activationService.activate(ActivatedSkillSet.empty(), "missing")
        );

        assertEquals("未找到 Skill: missing", exception.getMessage());
    }

    @Test
    void shouldTreatRepeatedActivationAsIdempotent() throws Exception {
        SkillActivationService activationService = createActivationService();
        writeSkill(
                tempRoot.resolve("workspace/.repopilot/skills/debug"),
                """
                        ---
                        name: debug
                        description: 结构化排查测试失败
                        ---
                        """,
                "## Debug Skill\n先复现，再缩小范围。\n"
        );

        SkillActivationResult firstActivation = activationService.activate(ActivatedSkillSet.empty(), "debug");
        SkillActivationResult secondActivation = activationService.activate(
                ActivatedSkillSet.fromMessages(firstActivation.appendedMessages()),
                "debug"
        );

        assertTrue(!secondActivation.activatedNow());
        assertEquals("debug", secondActivation.skillName());
        assertEquals("Skill debug 已激活。", secondActivation.output());
        assertEquals(List.of(), secondActivation.appendedMessages());
    }

    private SkillActivationService createActivationService() {
        Path workspaceRoot = tempRoot.resolve("workspace");
        Path userHome = tempRoot.resolve("home");
        return new SkillActivationService(SkillLoader.createDefault(workspaceRoot, userHome));
    }

    private void writeSkill(Path skillRoot, String frontMatter, String body) throws Exception {
        Files.createDirectories(skillRoot);
        Files.writeString(skillRoot.resolve("SKILL.md"), frontMatter + body);
    }
}
