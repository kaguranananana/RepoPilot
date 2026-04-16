package com.repopilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    @Test
    void shouldExecuteRegisteredTool() {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register("echo", "回显输入文本", arguments -> ToolExecutionResult.success(arguments.get("text")));

        ToolExecutionResult result = toolRegistry.execute("echo", Map.of("text", "hello"));

        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
        assertEquals("hello", result.output());
        assertEquals(1, toolRegistry.list().size());
    }

    @Test
    void shouldRejectUnknownToolExecution() {
        ToolRegistry toolRegistry = new ToolRegistry();

        assertThrows(ToolNotFoundException.class, () -> toolRegistry.execute("missing", Map.of()));
    }
}
