package com.repopilot.cli.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class InteractiveCliConfigTest {

    @Test
    void shouldDefaultTraceLevelToSummaryWhenEnvironmentVariableIsMissing() {
        InteractiveCliConfig config = InteractiveCliConfig.fromEnvironment(Map.of(
                "REPOPILOT_SERVER_BASE_URL", "http://127.0.0.1:8080",
                "REPOPILOT_WORKSPACE_ID", "workspace-001"
        ));

        assertEquals(TraceLevel.SUMMARY, config.traceLevel());
    }

    @Test
    void shouldParseVerboseTraceLevelFromEnvironment() {
        InteractiveCliConfig config = InteractiveCliConfig.fromEnvironment(Map.of(
                "REPOPILOT_SERVER_BASE_URL", "http://127.0.0.1:8080",
                "REPOPILOT_WORKSPACE_ID", "workspace-001",
                "REPOPILOT_TRACE_LEVEL", "verbose"
        ));

        assertEquals(TraceLevel.VERBOSE, config.traceLevel());
    }
}
