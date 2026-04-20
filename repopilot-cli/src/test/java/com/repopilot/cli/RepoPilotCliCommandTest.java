package com.repopilot.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class RepoPilotCliCommandTest {

    @Test
    void shouldStartInteractiveSessionWhenRootCommandRuns() {
        AtomicInteger startCount = new AtomicInteger();
        RepoPilotCliCommand command = new RepoPilotCliCommand(startCount::incrementAndGet);

        int exitCode = new CommandLine(command).execute();

        assertEquals(0, exitCode);
        assertEquals(1, startCount.get());
    }

    @Test
    void shouldRegisterEvalSubcommandOnRootCommand() {
        CommandLine commandLine = new CommandLine(new RepoPilotCliCommand());

        assertTrue(commandLine.getSubcommands().containsKey("eval"));
    }
}
