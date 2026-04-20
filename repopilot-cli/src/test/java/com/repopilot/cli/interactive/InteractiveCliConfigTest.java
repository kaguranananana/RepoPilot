package com.repopilot.cli.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals(12, config.maxSteps());
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

    @Test
    void shouldParseMaxStepsFromEnvironment() {
        InteractiveCliConfig config = InteractiveCliConfig.fromEnvironment(Map.of(
                "REPOPILOT_SERVER_BASE_URL", "http://127.0.0.1:8080",
                "REPOPILOT_WORKSPACE_ID", "workspace-001",
                "REPOPILOT_MAX_STEPS", "16"
        ));

        assertEquals(16, config.maxSteps());
    }

    @Test
    void shouldRejectInvalidMaxSteps() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> InteractiveCliConfig.fromEnvironment(Map.of(
                        "REPOPILOT_SERVER_BASE_URL", "http://127.0.0.1:8080",
                        "REPOPILOT_WORKSPACE_ID", "workspace-001",
                        "REPOPILOT_MAX_STEPS", "0"
                ))
        );

        assertEquals("REPOPILOT_MAX_STEPS must be greater than zero.", exception.getMessage());
    }
}
