package com.repopilot.cli.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserSkillCommandParserTest {

    private final UserSkillCommandParser parser = new UserSkillCommandParser();

    @Test
    void shouldParseSkillCommandWithRemainingPrompt() {
        UserSkillCommand command = parser.parse("/debug 修复这个测试").orElseThrow();

        assertEquals("debug", command.skillName());
        assertEquals("修复这个测试", command.remainingPrompt());
    }

    @Test
    void shouldParseDollarPrefixedSkillCommandWithoutRemainingPrompt() {
        UserSkillCommand command = parser.parse("$debug").orElseThrow();

        assertEquals("debug", command.skillName());
        assertEquals(null, command.remainingPrompt());
    }

    @Test
    void shouldRejectMultipleExplicitSkillCommandsInOneInput() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("/debug /review")
        );

        assertEquals("一次只能显式激活一个 Skill。", exception.getMessage());
    }

    @Test
    void shouldReturnEmptyWhenInputIsNotExplicitSkillCommand() {
        Optional<UserSkillCommand> result = parser.parse("请帮我修复这个测试");

        assertTrue(result.isEmpty());
    }
}
