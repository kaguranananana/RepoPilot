package com.repopilot.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.MessageRole;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillAllowedToolsIntegrationTest {

    @Test
    void shouldIntersectAllowedToolsAcrossActivatedSkillsAndIgnoreUnrestrictedSkill() {
        ActivatedSkillSet activatedSkillSet = ActivatedSkillSet.fromMessages(List.of(
                activatedSkillMessage("readonly-a", "read_file, grep_files"),
                activatedSkillMessage("readonly-b", "read_file"),
                activatedSkillMessage("debug", "")
        ));

        assertEquals(
                List.of("read_file"),
                activatedSkillSet.resolveEffectiveAllowedTools(List.of("read_file", "grep_files", "run_command"))
        );
    }

    @Test
    void shouldKeepGlobalToolsWhenNoActivatedSkillDeclaresAllowedTools() {
        ActivatedSkillSet activatedSkillSet = ActivatedSkillSet.fromMessages(List.of(
                activatedSkillMessage("debug", "")
        ));

        assertEquals(
                List.of("read_file", "grep_files", "run_command"),
                activatedSkillSet.resolveEffectiveAllowedTools(List.of("read_file", "grep_files", "run_command"))
        );
    }

    private static ConversationMessage activatedSkillMessage(String skillName, String allowedToolsLine) {
        return new ConversationMessage(
                MessageRole.SYSTEM,
                """
                        # Activated Skill
                        name: %s
                        source: project
                        allowed-tools: %s

                        ## Skill Body
                        约束说明。
                        """.formatted(skillName, allowedToolsLine).strip()
        );
    }
}
