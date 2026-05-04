package com.repopilot.cli.interactive;

import java.util.Objects;
import java.util.Optional;

/**
 * 解析显式记忆管理命令。
 * 这里坚持只接受固定命令语法，避免把普通文本错误识别成管理动作。
 */
public final class UserMemoryCommandParser {

    public Optional<UserMemoryCommand> parse(String input) {
        Objects.requireNonNull(input, "input must not be null.");
        String normalizedInput = input.strip();
        if (normalizedInput.isEmpty()) {
            return Optional.empty();
        }
        if (normalizedInput.equals("/remember")) {
            return Optional.of(new UserMemoryCommand(UserMemoryCommand.Type.REMEMBER, null));
        }
        if (normalizedInput.equals("/memories")) {
            return Optional.of(new UserMemoryCommand(UserMemoryCommand.Type.LIST, null));
        }
        if (normalizedInput.startsWith("/memory ")) {
            return Optional.of(new UserMemoryCommand(
                    UserMemoryCommand.Type.SHOW,
                    normalizedInput.substring("/memory ".length()).strip()
            ));
        }
        if (normalizedInput.startsWith("/forget ")) {
            return Optional.of(new UserMemoryCommand(
                    UserMemoryCommand.Type.FORGET,
                    normalizedInput.substring("/forget ".length()).strip()
            ));
        }
        return Optional.empty();
    }
}
