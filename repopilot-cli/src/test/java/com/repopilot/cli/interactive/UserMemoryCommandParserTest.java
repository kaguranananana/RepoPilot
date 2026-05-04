package com.repopilot.cli.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserMemoryCommandParserTest {

    private final UserMemoryCommandParser parser = new UserMemoryCommandParser();

    @Test
    void shouldParseRememberCommand() {
        UserMemoryCommand command = parser.parse("/remember").orElseThrow();

        assertEquals(UserMemoryCommand.Type.REMEMBER, command.type());
        assertEquals(Optional.empty(), command.id());
    }

    @Test
    void shouldParseListCommand() {
        UserMemoryCommand command = parser.parse("/memories").orElseThrow();

        assertEquals(UserMemoryCommand.Type.LIST, command.type());
        assertEquals(Optional.empty(), command.id());
    }

    @Test
    void shouldParseShowCommandWithId() {
        UserMemoryCommand command = parser.parse("/memory project-plan-execute-boundary").orElseThrow();

        assertEquals(UserMemoryCommand.Type.SHOW, command.type());
        assertEquals(Optional.of("project-plan-execute-boundary"), command.id());
    }

    @Test
    void shouldParseForgetCommandWithId() {
        UserMemoryCommand command = parser.parse("/forget project-plan-execute-boundary").orElseThrow();

        assertEquals(UserMemoryCommand.Type.FORGET, command.type());
        assertEquals(Optional.of("project-plan-execute-boundary"), command.id());
    }

    @Test
    void shouldIgnoreNormalPrompt() {
        assertTrue(parser.parse("分析 README.md").isEmpty());
    }
}
